//! AirPlay 2 的 HTTP/RTSP 配对端点：`POST /pair-setup`、`POST /pair-verify`。

use crate::airplay2;
use crate::pairing::{PairSetup, PairVerify};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;

struct Ctx {
    device_id: Vec<u8>,
    identity: ed25519_dalek::SigningKey,
    identity_pub: [u8; 32],
    setup: Mutex<Option<PairSetup>>,
    verify: Mutex<Option<PairVerify>>,
}

/// 在指定端口上提供配对端点（端口 0 表示随机）。
pub fn spawn(port: u16) -> std::io::Result<u16> {
    let listener = TcpListener::bind(("0.0.0.0", port))?;
    let actual = listener.local_addr()?.port();
    let (identity, identity_pub) = crate::crypto::ed25519_keypair();
    let ctx = Arc::new(Ctx {
        device_id: b"AA:BB:CC:DD:EE:FF".to_vec(),
        identity,
        identity_pub,
        setup: Mutex::new(None),
        verify: Mutex::new(None),
    });
    thread::spawn(move || {
        for stream in listener.incoming().flatten() {
            let ctx = ctx.clone();
            thread::spawn(move || {
                let _ = handle(stream, &ctx);
            });
        }
    });
    Ok(actual)
}

fn handle(mut s: TcpStream, ctx: &Ctx) -> std::io::Result<()> {
    let mut reader = BufReader::new(s.try_clone()?);
    let mut first = String::new();
    reader.read_line(&mut first)?;
    let mut it = first.split_whitespace();
    let method = it.next().unwrap_or("").to_string();
    let path = it.next().unwrap_or("").to_string();

    let mut len = 0usize;
    let mut cseq = String::from("0");
    loop {
        let mut line = String::new();
        if reader.read_line(&mut line)? == 0 {
            break;
        }
        let t = line.trim();
        if t.is_empty() {
            break;
        }
        if let Some((k, v)) = t.split_once(':') {
            if k.eq_ignore_ascii_case("content-length") {
                len = v.trim().parse().unwrap_or(0);
            } else if k.eq_ignore_ascii_case("cseq") {
                cseq = v.trim().to_string();
            }
        }
    }
    log::info!("airplay2 {method} {path:?} len={len}");
    let mut body = vec![0u8; len];
    if len > 0 {
        reader.read_exact(&mut body)?;
    }

    let (code, ctype, payload) = match (method.as_str(), path.as_str()) {
        ("GET", "/info") => (200, "application/x-apple-binary-plist", info_plist(&ctx.device_id, &ctx.identity_pub)),
        ("POST", "/pair-setup") => {
            let mut guard = ctx.setup.lock().unwrap();
            let setup = guard.get_or_insert_with(|| PairSetup::new(airplay2::PAIRING_PASSWORD));
            let out = setup.handle(&body);
            if setup.is_done() {
                *guard = None;
            }
            (200, "application/octet-stream", out)
        }
        ("POST", "/pair-verify") => {
            let mut guard = ctx.verify.lock().unwrap();
            let verify = guard.get_or_insert_with(|| {
                PairVerify::with_key(&ctx.device_id, ctx.identity.clone())
            });
            let out = verify.handle(&body);
            if verify.session_key().is_some() {
                *guard = None;
            }
            (200, "application/octet-stream", out)
        }
        ("POST", "/fp-setup") => (
            200,
            "application/octet-stream",
            crate::fairplay::fp_setup(&body).unwrap_or_default(),
        ),
        ("POST", "/auth-setup") => (403, "text/plain", b"no fairplay".to_vec()),
        _ => (404, "text/plain", b"not found".to_vec()),
    };

    // AirPlay 2 用的是 RTSP：响应必须是 RTSP/1.0 并回显 CSeq
    let head = format!(
        "RTSP/1.0 {code} OK\r\nCSeq: {cseq}\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        payload.len()
    );
    s.write_all(head.as_bytes())?;
    s.write_all(&payload)?;
    s.flush()
}

fn info_plist(device_id: &[u8], pk: &[u8; 32]) -> Vec<u8> {
    use plist::{Dictionary, Value};
    let mut d = Dictionary::new();
    d.insert("deviceID".into(), Value::String(String::from_utf8_lossy(device_id).to_string()));
    d.insert("features".into(), Value::Integer((crate::airplay2::FEATURES as i64).into()));
    d.insert("statusFlags".into(), Value::Integer(0x4i64.into()));
    d.insert("model".into(), Value::String("AppleTV3,2".into()));
    d.insert("manufacturer".into(), Value::String("F TV".into()));
    d.insert("name".into(), Value::String("F TV 桌面".into()));
    d.insert("nameIsFactoryDefault".into(), Value::Boolean(false));
    d.insert("protovers".into(), Value::String("1.0".into()));
    d.insert("protocolVersion".into(), Value::String("1.1".into()));
    d.insert("srcvers".into(), Value::String("220.68".into()));
    d.insert("sourceVersion".into(), Value::String("220.68".into()));
    d.insert("sdk".into(), Value::String("AirPlay;2.0.2".into()));
    d.insert("vv".into(), Value::Integer(2i64.into()));
    d.insert("pi".into(), Value::String("b8b7c5a1-2f3c-4d5e-9a1b-3c4d5e6f7a8b".into()));
    d.insert("pk".into(), Value::Data(pk.to_vec()));
    d.insert("keepAliveLowPower".into(), Value::Boolean(true));
    d.insert("keepAliveSendStatsAsBody".into(), Value::Boolean(true));

    // 音频能力：AirPlay 2 必须给，否则会被判成不支持
    let mut lat = Dictionary::new();
    lat.insert("inputLatencyMicros".into(), Value::Integer(0i64.into()));
    lat.insert("outputLatencyMicros".into(), Value::Integer(400000i64.into()));
    d.insert("audioLatencies".into(), Value::Array(vec![Value::Dictionary(lat)]));

    let mut formats = Dictionary::new();
    formats.insert("audioStream".into(), Value::Integer(21235712i64.into()));
    formats.insert("bufferStream".into(), Value::Integer(14680064i64.into()));
    d.insert("supportedFormats".into(), Value::Dictionary(formats));

    let mut out = Vec::new();
    Value::Dictionary(d).to_writer_binary(&mut out).expect("plist");
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::tlv8;

    fn post(port: u16, path: &str, body: &[u8]) -> Vec<u8> {
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        let head = format!(
            "POST {path} HTTP/1.1\r\nHost: x\r\nContent-Length: {}\r\n\r\n",
            body.len()
        );
        s.write_all(head.as_bytes()).unwrap();
        s.write_all(body).unwrap();
        s.flush().unwrap();
        let mut buf = Vec::new();
        s.read_to_end(&mut buf).unwrap();
        let split = buf.windows(4).position(|w| w == b"\r\n\r\n").unwrap() + 4;
        buf[split..].to_vec()
    }

    #[test]
    fn pair_setup_endpoint_returns_salt_and_pubkey() {
        let port = spawn(0).unwrap();
        let req = tlv8::encode(&[(tlv8::STATE, &[1]), (tlv8::METHOD, &[0]), (tlv8::FLAGS, &[0x10])]);
        let resp = post(port, "/pair-setup", &req);
        let items = tlv8::decode(&resp);
        assert_eq!(tlv8::get(&items, tlv8::STATE), Some(&[2u8][..]));
        assert_eq!(tlv8::get(&items, 0x02).unwrap().len(), 16);
        assert_eq!(tlv8::get(&items, tlv8::PUBLIC_KEY).unwrap().len(), 384);
    }

    #[test]
    fn info_returns_binary_plist() {
        let port = spawn(0).unwrap();
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.write_all(b"GET /info HTTP/1.1\r\nHost: x\r\n\r\n").unwrap();
        let mut buf = Vec::new();
        s.read_to_end(&mut buf).unwrap();
        let split = buf.windows(4).position(|w| w == b"\r\n\r\n").unwrap() + 4;
        let body = &buf[split..];
        assert!(body.starts_with(b"bplist00"), "应是二进制 plist");
        let v = plist::Value::from_reader(std::io::Cursor::new(body)).unwrap();
        let d = v.as_dictionary().unwrap();
        assert!(d.contains_key("features"));
        assert!(d.contains_key("pk"));
        assert_eq!(d.get("model").unwrap().as_string(), Some("AppleTV3,2"));
    }

    #[test]
    fn unknown_path_is_404() {
        let port = spawn(0).unwrap();
        let resp = post(port, "/nope", b"");
        assert_eq!(resp, b"not found");
    }
}
