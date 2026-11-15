//! AirPlay 2 的 HTTP/RTSP 端点：配对、`/info`、`/fp-setup`、
//! `SETUP`（音频密钥与 streams）。

use crate::airplay2;
use crate::fairplay::FpSession;
use crate::pairing::{PairSetup, PairVerify};
use plist::{Dictionary, Value};
use std::io::{BufRead, BufReader, Cursor, Read, Write};
use std::net::{TcpListener, TcpStream, UdpSocket};
use std::sync::{Arc, Mutex};
use std::thread;

/// 与抓包里的 AirReceiver 一致；iPhone 会在后续请求里回显这个值。
const SESSION_ID: &str = "DEADBEEF";

struct Ctx {
    device_id: Vec<u8>,
    identity: ed25519_dalek::SigningKey,
    identity_pub: [u8; 32],
    setup: Mutex<Option<PairSetup>>,
    verify: Mutex<Option<PairVerify>>,
    fp: Mutex<FpSession>,
    audio: Mutex<AudioSession>,
}

/// `/setup` 建立的会话：FairPlay AES key/IV + 音频端口。
#[derive(Default)]
struct AudioSession {
    key: Option<[u8; 16]>,
    iv: Option<[u8; 16]>,
    session_active: bool,
    timing_port: u16,
    event_port: u16,
    data_port: u16,
    control_port: u16,
}

impl AudioSession {
    /// 参考实现 `Session(plist, keymsg)`：`ekey`/`eiv` 走 FairPlay3，
    /// `streams` 则分配音频端口。字段名来自 /tmp/ap3.pcap。
    fn handle_setup(&mut self, body: &[u8], fp: &FpSession) -> Result<Vec<u8>, String> {
        let value = Value::from_reader(Cursor::new(body))
            .map_err(|e| format!("SETUP plist 解析失败: {e}"))?;
        let dict = value
            .as_dictionary()
            .ok_or_else(|| "SETUP body 不是 plist 字典".to_string())?;

        if let (Some(ekey), Some(eiv)) = (dict.get("ekey"), dict.get("eiv")) {
            let ekey = ekey.as_data().ok_or_else(|| "ekey 不是 data".to_string())?;
            let eiv = eiv.as_data().ok_or_else(|| "eiv 不是 data".to_string())?;
            if eiv.len() != 16 {
                return Err(format!("eiv 长度 {}，预期 16", eiv.len()));
            }
            let key = fp.decrypt_aes_key(ekey).ok_or_else(|| {
                "ekey 解密失败（缺 fp-setup seq3 keymsg，或 ekey 不是 72 字节）".to_string()
            })?;
            self.key = Some(key);
            self.iv = Some(eiv.try_into().expect("已经校验长度"));
            self.session_active = true;
            self.ensure_timing()?;
            log::info!("SETUP: ekey 解密成功，音频 key/iv 已保存");

            let mut d = Dictionary::new();
            d.insert("timingPort".into(), Value::Integer(i64::from(self.timing_port).into()));
            d.insert("eventPort".into(), Value::Integer(i64::from(self.event_port).into()));
            return Ok(encode_plist(Value::Dictionary(d)));
        }

        if let Some(streams) = dict.get("streams").and_then(Value::as_array) {
            let stream_type = streams
                .first()
                .and_then(Value::as_dictionary)
                .and_then(|d| d.get("type"))
                .and_then(Value::as_signed_integer)
                .unwrap_or(96);
            if self.key.is_none() {
                log::warn!("SETUP streams 先于 ekey 到达，音频将无法解密");
            }
            self.ensure_stream()?;
            self.session_active = true;

            let mut desc = Dictionary::new();
            desc.insert("controlPort".into(), Value::Integer(i64::from(self.control_port).into()));
            desc.insert("type".into(), Value::Integer(stream_type.into()));
            desc.insert("dataPort".into(), Value::Integer(i64::from(self.data_port).into()));

            let mut d = Dictionary::new();
            d.insert("streams".into(), Value::Array(vec![Value::Dictionary(desc)]));
            return Ok(encode_plist(Value::Dictionary(d)));
        }

        Err("SETUP body 既没有 ekey/eiv，也没有 streams".into())
    }

    fn ensure_timing(&mut self) -> Result<(), String> {
        if self.timing_port != 0 {
            return Ok(());
        }
        let timing = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        let event = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        self.timing_port = timing.local_addr().map_err(|e| e.to_string())?.port();
        self.event_port = event.local_addr().map_err(|e| e.to_string())?.port();
        thread::spawn(move || drain_udp(timing, "timing"));
        thread::spawn(move || drain_udp(event, "event"));
        Ok(())
    }

    fn ensure_stream(&mut self) -> Result<(), String> {
        if self.data_port != 0 {
            return Ok(());
        }
        let listener = TcpListener::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        let control = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        self.data_port = listener.local_addr().map_err(|e| e.to_string())?.port();
        self.control_port = control.local_addr().map_err(|e| e.to_string())?.port();
        thread::spawn(move || {
            for conn in listener.incoming().flatten() {
                thread::spawn(move || drain_audio(conn));
            }
        });
        thread::spawn(move || drain_udp(control, "audio-control"));
        Ok(())
    }
}

fn encode_plist(value: Value) -> Vec<u8> {
    let mut out = Vec::new();
    value.to_writer_binary(&mut out).expect("plist 编码");
    out
}

fn drain_udp(socket: UdpSocket, tag: &'static str) {
    let mut buf = [0u8; 2048];
    loop {
        match socket.recv_from(&mut buf) {
            Ok((n, from)) => log::debug!("{tag} 收到 {n} 字节 from {from}"),
            Err(e) => {
                log::warn!("{tag} 接收失败: {e}");
                break;
            }
        }
    }
}

/// buffered audio：每个 RTP 包前有 2 字节大端长度。当前只收不播，
/// 后续 `/audio` 解密与出声在这里接。
fn drain_audio(mut stream: TcpStream) {
    let mut len_buf = [0u8; 2];
    let mut frames = 0usize;
    let mut bytes = 0usize;
    loop {
        if stream.read_exact(&mut len_buf).is_err() {
            break;
        }
        let n = u16::from_be_bytes(len_buf) as usize;
        if n < 2 {
            log::warn!("audio 帧长度非法: {n}");
            break;
        }
        let mut data = vec![0u8; n - 2];
        if stream.read_exact(&mut data).is_err() {
            break;
        }
        frames += 1;
        bytes += n;
        if frames == 1 {
            let head = &data[..data.len().min(16)];
            log::info!("audio 首帧 len={n} head={head:02x?}");
        }
    }
    log::info!("audio 连接结束：{frames} 帧 / {bytes} 字节");
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
        fp: Mutex::new(FpSession::new()),
        audio: Mutex::new(AudioSession::default()),
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

    let mut session: Option<&'static str> = None;
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
        ("POST", "/fp-setup") => {
            // seq3 的 164 字节请求体要留着：SDP/ANNOUNCE 里的 72 字节
            // FairPlay AES key 需要它才能解密。
            let mut guard = ctx.fp.lock().unwrap();
            let out = guard.handle(&body).unwrap_or_default();
            (200, "application/octet-stream", out)
        },
        ("POST", "/auth-setup") => (403, "text/plain", b"no fairplay".to_vec()),
        ("SETUP", p) if p.starts_with("rtsp://") => {
            let fp = ctx.fp.lock().unwrap();
            let mut audio = ctx.audio.lock().unwrap();
            match audio.handle_setup(&body, &fp) {
                Ok(payload) => {
                    session = Some(SESSION_ID);
                    (200, "application/x-apple-binary-plist", payload)
                }
                Err(e) => {
                    log::warn!("SETUP 失败: {e}");
                    (500, "text/plain", e.into_bytes())
                }
            }
        }
        // 以下响应形态来自 /tmp/ap3.pcap；先保证控制流程能走到第二次
        // SETUP streams 和音频连接。
        ("GET_PARAMETER", p) if p.starts_with("rtsp://") => {
            let payload = if body.windows(6).any(|w| w == b"volume") {
                b"volume: 0.000000\r\n".to_vec()
            } else {
                Vec::new()
            };
            (200, "text/parameters", payload)
        }
        ("RECORD", _) | ("FLUSH", _) | ("TEARDOWN", _) => {
            (200, "application/octet-stream", Vec::new())
        }
        ("SET_PARAMETER", _) => {
            (200, "text/plain", Vec::new())
        }
        ("POST", "/audioMode") | ("POST", "/feedback") => {
            (200, "application/octet-stream", Vec::new())
        }
        _ => (404, "text/plain", b"not found".to_vec()),
    };

    if session.is_none() && ctx.audio.lock().unwrap().session_active {
        session = Some(SESSION_ID);
    }

    // AirPlay 2 用的是 RTSP：响应必须是 RTSP/1.0 并回显 CSeq
    let mut head = format!(
        "RTSP/1.0 {code} OK\r\nCSeq: {cseq}\r\nAudio-Jack-Status: connected; type=digital\r\n"
    );
    if let Some(id) = session {
        head.push_str(&format!("Session: {id}\r\n"));
    }
    head.push_str(&format!(
        "Content-Type: {ctype}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        payload.len()
    ));
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

    // 真实抓包 /tmp/ap3.pcap 里的原始报文。
    const FP_SEQ3: &[u8] =
        include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/ap3_fp_seq3.bin"));
    const SETUP1: &[u8] =
        include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/ap3_setup1.bplist"));
    const SETUP2: &[u8] =
        include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/ap3_setup2.bplist"));

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

    fn request_raw(port: u16, first_line: &str, body: &[u8]) -> (String, Vec<u8>) {
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        let head = format!(
            "{first_line}\r\nCSeq: 2\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: {}\r\n\r\n",
            body.len()
        );
        s.write_all(head.as_bytes()).unwrap();
        s.write_all(body).unwrap();
        s.flush().unwrap();
        let mut buf = Vec::new();
        s.read_to_end(&mut buf).unwrap();
        let split = buf.windows(4).position(|w| w == b"\r\n\r\n").unwrap() + 4;
        (
            String::from_utf8_lossy(&buf[..split]).to_string(),
            buf[split..].to_vec(),
        )
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

    #[test]
    fn fp_setup_endpoint_serves_seq1_and_seq3() {
        use crate::fairplay3::{test_vectors::AES_CASES, unhex};

        let port = spawn(0).unwrap();
        let mut seq1 = b"FPLY".to_vec();
        seq1.extend_from_slice(&[3, 1, 1, 0, 0, 0, 0, 0, 0, 0]);
        seq1.push(2); // mode
        seq1.push(0);
        let resp1 = post(port, "/fp-setup", &seq1);
        assert_eq!(resp1.len(), 142);
        assert_eq!(&resp1[..4], b"FPLY");

        let case = AES_CASES
            .iter()
            .find(|c| c.name == "fp-seq3")
            .expect("缺少 fp-seq3 向量");
        let seq3 = unhex(case.message);
        let resp2 = post(port, "/fp-setup", &seq3);
        assert_eq!(resp2.len(), 32);
        assert_eq!(&resp2[..12], b"FPLY\x03\x01\x04\x00\x00\x00\x00\x14");
        assert_eq!(&resp2[12..], &seq3[seq3.len() - 20..]);
    }

    #[test]
    fn setup_decrypts_captured_ekey() {
        use crate::fairplay3::unhex;

        let mut fp = FpSession::new();
        assert!(fp.handle(FP_SEQ3).is_some());
        let mut audio = AudioSession::default();
        let resp = audio.handle_setup(SETUP1, &fp).expect("真实 SETUP 应成功");

        assert_eq!(
            audio.key.expect("应保存 AES key").to_vec(),
            unhex("38c9b755988270948b207e9e67ee18ff")
        );
        assert_eq!(
            audio.iv.expect("应保存 AES IV").to_vec(),
            unhex("4be9c99bf6fe53b84e26c0c33198ee58")
        );

        let v = Value::from_reader(Cursor::new(resp)).unwrap();
        let d = v.as_dictionary().unwrap();
        assert_eq!(
            d.get("timingPort").and_then(Value::as_signed_integer),
            Some(i64::from(audio.timing_port))
        );
        assert_eq!(
            d.get("eventPort").and_then(Value::as_signed_integer),
            Some(i64::from(audio.event_port))
        );
        assert!(audio.timing_port > 0 && audio.event_port > 0);
    }

    #[test]
    fn setup_streams_allocates_audio_ports() {
        let fp = FpSession::new();
        let mut audio = AudioSession::default();
        let resp = audio
            .handle_setup(SETUP2, &fp)
            .expect("streams SETUP 应成功");
        let v = Value::from_reader(Cursor::new(resp)).unwrap();
        let d = v.as_dictionary().unwrap();
        let streams = d.get("streams").and_then(Value::as_array).unwrap();
        let s = streams[0].as_dictionary().unwrap();
        assert_eq!(s.get("type").and_then(Value::as_signed_integer), Some(96));
        assert_eq!(
            s.get("dataPort").and_then(Value::as_signed_integer),
            Some(i64::from(audio.data_port))
        );
        assert_eq!(
            s.get("controlPort").and_then(Value::as_signed_integer),
            Some(i64::from(audio.control_port))
        );
        assert!(audio.data_port > 0 && audio.control_port > 0);
    }

    #[test]
    fn control_requests_are_accepted() {
        let port = spawn(0).unwrap();
        let (head, body) = request_raw(port, "GET_PARAMETER rtsp://192.168.10.155/x RTSP/1.0", b"volume\r\n");
        assert!(head.contains("RTSP/1.0 200 OK"));
        assert_eq!(body, b"volume: 0.000000\r\n");

        let (head2, body2) = request_raw(port, "RECORD rtsp://192.168.10.155/x RTSP/1.0", b"");
        assert!(head2.contains("RTSP/1.0 200 OK"));
        assert!(body2.is_empty());
    }

    #[test]
    fn setup_endpoint_runs_captured_flow() {
        let port = spawn(0).unwrap();

        // 先过 /fp-setup seq3，保存 164 字节 keymsg。
        let fp_resp = post(port, "/fp-setup", FP_SEQ3);
        assert_eq!(fp_resp.len(), 32);

        let uri = "rtsp://192.168.10.155/13418177187544911696";
        let (head, body) = request_raw(port, &format!("SETUP {uri} RTSP/1.0"), SETUP1);
        assert!(head.contains("RTSP/1.0 200 OK"));
        assert!(head.contains("Session: DEADBEEF"));
        let v = Value::from_reader(Cursor::new(body)).unwrap();
        let d = v.as_dictionary().unwrap();
        assert!(d.get("timingPort").and_then(Value::as_signed_integer).unwrap() > 0);
        assert!(d.get("eventPort").and_then(Value::as_signed_integer).unwrap() > 0);

        let (head2, body2) = request_raw(port, &format!("SETUP {uri} RTSP/1.0"), SETUP2);
        assert!(head2.contains("Session: DEADBEEF"));
        let v2 = Value::from_reader(Cursor::new(body2)).unwrap();
        let streams = v2
            .as_dictionary()
            .and_then(|d| d.get("streams"))
            .and_then(Value::as_array)
            .unwrap();
        let s = streams[0].as_dictionary().unwrap();
        assert_eq!(s.get("type").and_then(Value::as_signed_integer), Some(96));
        assert!(s.get("dataPort").and_then(Value::as_signed_integer).unwrap() > 0);
        assert!(s.get("controlPort").and_then(Value::as_signed_integer).unwrap() > 0);
    }
}
