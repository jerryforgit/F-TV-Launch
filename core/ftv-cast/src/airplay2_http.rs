//! AirPlay 2 的 HTTP/RTSP 配对端点：`POST /pair-setup`、`POST /pair-verify`。

use crate::airplay2;
use crate::pairing::{PairSetup, PairVerify};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::{Arc, Mutex};
use std::thread;

struct Ctx {
    device_id: Vec<u8>,
    setup: Mutex<Option<PairSetup>>,
    verify: Mutex<Option<PairVerify>>,
}

/// 在指定端口上提供配对端点（端口 0 表示随机）。
pub fn spawn(port: u16) -> std::io::Result<u16> {
    let listener = TcpListener::bind(("0.0.0.0", port))?;
    let actual = listener.local_addr()?.port();
    let ctx = Arc::new(Ctx {
        device_id: b"F-TV-Desktop".to_vec(),
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
            }
        }
    }
    log::info!("airplay2 {method} {path} len={len}");
    let mut body = vec![0u8; len];
    if len > 0 {
        reader.read_exact(&mut body)?;
    }

    let (code, ctype, payload) = match (method.as_str(), path.as_str()) {
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
            let verify = guard.get_or_insert_with(|| PairVerify::new(&ctx.device_id));
            let out = verify.handle(&body);
            if verify.session_key().is_some() {
                *guard = None;
            }
            (200, "application/octet-stream", out)
        }
        ("POST", "/auth-setup") => (403, "text/plain", b"no fairplay".to_vec()),
        _ => (404, "text/plain", b"not found".to_vec()),
    };

    let head = format!(
        "HTTP/1.1 {code} OK\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        payload.len()
    );
    s.write_all(head.as_bytes())?;
    s.write_all(&payload)?;
    s.flush()
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
    fn unknown_path_is_404() {
        let port = spawn(0).unwrap();
        let resp = post(port, "/nope", b"");
        assert_eq!(resp, b"not found");
    }
}
