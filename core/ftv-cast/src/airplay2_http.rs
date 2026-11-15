//! AirPlay 2 的 HTTP/RTSP 端点：配对、`/info`、`/fp-setup`、
//! `SETUP`（音频密钥与 streams）。

use crate::airplay2;
use crate::audio_out;
use crate::fairplay::FpSession;
use crate::pairing::{PairSetup, PairVerify};
use aes::Aes128;
use cbc::cipher::{block_padding::NoPadding, BlockDecryptMut, KeyIvInit};
use plist::{Dictionary, Value};
use sha2::{Digest, Sha512};
use std::io::{BufRead, BufReader, Cursor, Read, Write};
use std::net::{IpAddr, SocketAddr, TcpListener, TcpStream, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

type Aes128CbcDec = cbc::Decryptor<Aes128>;

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

/// `/setup` 建立的会话：FairPlay key/IV、pair-verify 共享秘密和音频端口。
#[derive(Default)]
struct AudioSession {
    /// FairPlay `/fp-setup` + SETUP 解出的 16 字节 key。
    fp_key: Option<[u8; 16]>,
    /// pair-verify 的原始 X25519 共享秘密；没有配对时音频 key 就是 `fp_key`。
    shared_secret: Option<[u8; 32]>,
    /// SETUP 里的 `eiv`，每个 RTP 包都从它重新开始 CBC。
    eiv: Option<[u8; 16]>,
    key: Option<[u8; 16]>,
    session_active: bool,
    timing_port: u16,
    event_port: u16,
    data_port: u16,
    control_port: u16,
    audio_started: bool,
    ntp_stop: Option<Arc<AtomicBool>>,
    audio_stop: Option<Arc<AtomicBool>>,
}

impl AudioSession {
    /// 参考实现 `Session(plist, keymsg)`：
    /// - `ekey`/`eiv`：解密 FairPlay key，必要时和 ECDH 秘密做 SHA-512 派生；
    /// - `streams`：给 type 96 分配 UDP 音频/控制端口。
    /// 字段名来自 /tmp/ap3.pcap 与 AirBridge 的实测实现。
    fn handle_setup(
        &mut self,
        body: &[u8],
        fp: &FpSession,
        peer_ip: Option<IpAddr>,
    ) -> Result<Vec<u8>, String> {
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
            let fp_key = fp.decrypt_aes_key(ekey).ok_or_else(|| {
                "ekey 解密失败（缺 fp-setup seq3 keymsg，或 ekey 不是 72 字节）".to_string()
            })?;
            self.fp_key = Some(fp_key);
            self.eiv = Some(eiv.try_into().expect("已经校验长度"));
            audio_out::clear();
            self.derive_key();
            self.session_active = true;

            let client_timing = dict
                .get("timingPort")
                .and_then(Value::as_signed_integer)
                .and_then(|v| u16::try_from(v).ok());
            self.ensure_timing(peer_ip, client_timing)?;
            log::info!("SETUP: ekey 解密成功，音频 key/iv 已保存");

            let mut d = Dictionary::new();
            d.insert("timingPort".into(), Value::Integer(i64::from(self.timing_port).into()));
            d.insert("eventPort".into(), Value::Integer(i64::from(self.event_port).into()));
            return Ok(encode_plist(Value::Dictionary(d)));
        }

        if let Some(streams) = dict.get("streams").and_then(Value::as_array) {
            if self.key.is_none() {
                return Err("SETUP streams 先于 ekey 到达，音频 key 尚未就绪".into());
            }
            let stream = streams
                .first()
                .and_then(Value::as_dictionary)
                .ok_or_else(|| "streams[0] 不是字典".to_string())?;
            let stream_type = stream
                .get("type")
                .and_then(Value::as_signed_integer)
                .unwrap_or(96);
            self.ensure_stream(stream)?;
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

    fn set_shared_secret(&mut self, secret: [u8; 32]) {
        self.shared_secret = Some(secret);
        self.derive_key();
    }

    fn derive_key(&mut self) {
        let Some(fp_key) = self.fp_key else {
            return;
        };
        if self.eiv.is_none() {
            return;
        }
        let key = match self.shared_secret {
            Some(secret) => {
                let mut h = Sha512::new();
                h.update(fp_key);
                h.update(secret);
                let digest = h.finalize();
                let mut k = [0u8; 16];
                k.copy_from_slice(&digest[..16]);
                k
            }
            None => fp_key,
        };
        self.key = Some(key);
        log::info!(
            "SETUP: 音频 key 已派生（{}）",
            if self.shared_secret.is_some() {
                "FairPlay + ECDH"
            } else {
                "仅 FairPlay"
            }
        );
    }

    fn ensure_timing(
        &mut self,
        peer_ip: Option<IpAddr>,
        client_timing_port: Option<u16>,
    ) -> Result<(), String> {
        if self.timing_port != 0 {
            return Ok(());
        }
        let timing = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        // eventPort 是 TCP（HAP 加密事件通道），timingPort 才是 UDP/NTP。
        let event = TcpListener::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        self.timing_port = timing.local_addr().map_err(|e| e.to_string())?.port();
        self.event_port = event.local_addr().map_err(|e| e.to_string())?.port();

        if let (Some(ip), Some(port)) = (peer_ip, client_timing_port) {
            let stop = Arc::new(AtomicBool::new(false));
            if let Some(old) = self.ntp_stop.replace(stop.clone()) {
                old.store(true, Ordering::Relaxed);
            }
            if let Ok(sock) = timing.try_clone() {
                start_ntp(sock, SocketAddr::new(ip, port), stop);
            }
        }
        thread::spawn(move || drain_udp(timing, "timing"));
        thread::spawn(move || {
            for conn in event.incoming().flatten() {
                thread::spawn(move || drain_tcp(conn, "event"));
            }
        });
        Ok(())
    }

    fn ensure_stream(&mut self, stream: &Dictionary) -> Result<(), String> {
        if self.data_port != 0 {
            return Ok(());
        }
        let key = self.key.ok_or_else(|| "音频 key 未就绪".to_string())?;
        let iv = self.eiv.ok_or_else(|| "eiv 未就绪".to_string())?;

        let data = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        let control = UdpSocket::bind(("0.0.0.0", 0)).map_err(|e| e.to_string())?;
        self.data_port = data.local_addr().map_err(|e| e.to_string())?.port();
        self.control_port = control.local_addr().map_err(|e| e.to_string())?.port();

        let ct = stream
            .get("ct")
            .and_then(Value::as_signed_integer)
            .unwrap_or(2);
        let spf = stream
            .get("spf")
            .and_then(Value::as_signed_integer)
            .unwrap_or(352);
        let audio_format = stream
            .get("audioFormat")
            .and_then(Value::as_signed_integer)
            .unwrap_or(262_144);
        log::info!("SETUP streams: type=96 ct={ct} spf={spf} audioFormat={audio_format}");

        if ct != 2 {
            log::warn!("暂不支持 ct={ct}（当前只接 ALAC ct=2）");
            return Ok(());
        }
        let fmt = alac_format(spf, audio_format);
        let stop = Arc::new(AtomicBool::new(false));
        if let Some(old) = self.audio_stop.replace(stop.clone()) {
            old.store(true, Ordering::Relaxed);
        }
        self.audio_started = true;
        thread::spawn(move || run_audio(data, control, key, iv, fmt, stop));
        Ok(())
    }
}

/// 构造 ALAC `StreamInfo` 需要的 SDP fmtp 参数。
/// audioFormat 位定义来自 openairplay 的 `AirplayAudFmt`。
fn alac_format(spf: i64, audio_format: i64) -> String {
    let bits = if audio_format & (1 << 19) != 0 { 24 } else { 16 };
    let rate = if audio_format & ((1 << 20) | (1 << 21)) != 0 {
        48_000
    } else {
        44_100
    };
    format!("{spf} 0 {bits} 40 10 14 2 255 0 0 {rate}")
}

fn ntp_now() -> [u8; 8] {
    let d = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = d.as_secs() + 2_208_988_800;
    let frac = ((d.subsec_nanos() as u64) << 32) / 1_000_000_000;
    let mut b = [0u8; 8];
    b[..4].copy_from_slice(&(secs as u32).to_be_bytes());
    b[4..].copy_from_slice(&(frac as u32).to_be_bytes());
    b
}

/// iPhone 要求接收端主动发 NTP 请求；不发会断开连接。
fn start_ntp(socket: UdpSocket, remote: SocketAddr, stop: Arc<AtomicBool>) {
    thread::spawn(move || {
        let _ = socket.set_read_timeout(Some(Duration::from_millis(250)));
        let mut request = [0u8; 32];
        request[0] = 0x80;
        request[1] = 0xd2;
        request[3] = 0x07;
        let mut buf = [0u8; 128];
        let mut sent = 0u32;
        while !stop.load(Ordering::Relaxed) {
            request[24..32].copy_from_slice(&ntp_now());
            let _ = socket.send_to(&request, remote);
            sent += 1;
            let _ = socket.recv(&mut buf);
            let pause_ms = if sent > 30 { 3000 } else { 250 };
            for _ in 0..(pause_ms / 50) {
                if stop.load(Ordering::Relaxed) {
                    return;
                }
                thread::sleep(Duration::from_millis(50));
            }
        }
    });
}

fn decrypt_rtp(key: &[u8; 16], iv: &[u8; 16], packet: &[u8]) -> Option<Vec<u8>> {
    let payload = packet.get(12..)?;
    let whole = payload.len() / 16 * 16;
    let mut out = payload.to_vec();
    Aes128CbcDec::new(key.into(), iv.into())
        .decrypt_padded_mut::<NoPadding>(&mut out[..whole])
        .ok()?;
    Some(out)
}

fn run_audio(
    data: UdpSocket,
    control: UdpSocket,
    key: [u8; 16],
    iv: [u8; 16],
    fmt: String,
    stop: Arc<AtomicBool>,
) {
    let stop_control = stop.clone();
    thread::spawn(move || {
        let _ = control.set_read_timeout(Some(Duration::from_millis(500)));
        let mut buf = [0u8; 2048];
        while !stop_control.load(Ordering::Relaxed) {
            let _ = control.recv(&mut buf);
        }
    });

    let info = match alac::StreamInfo::from_sdp_format_parameters(&fmt) {
        Ok(info) => info,
        Err(e) => {
            log::warn!("ALAC 参数无效 ({fmt}): {e}");
            return;
        }
    };
    let (rate, channels) = (info.sample_rate(), info.channels());
    let mut decoder = alac::Decoder::new(info);
    audio_out::set_format(rate, channels as u32);
    log::info!("audio: ALAC {rate} Hz / {channels} ch 已启动");

    let _ = data.set_read_timeout(Some(Duration::from_millis(500)));
    let mut packet = [0u8; 4096];
    let mut pcm = vec![0i16; 8192];
    let mut next_seq: Option<u16> = None;
    let (mut received, mut decoded, mut errors) = (0u64, 0u64, 0u32);

    while !stop.load(Ordering::Relaxed) {
        let len = match data.recv(&mut packet) {
            Ok(n) => n,
            Err(_) => continue,
        };
        if len < 13 {
            continue;
        }
        if packet[1] & 0x7f != 96 {
            continue;
        }
        let seq = u16::from_be_bytes([packet[2], packet[3]]);
        if let Some(next) = next_seq {
            let diff = seq.wrapping_sub(next) as i16;
            if !(0..=64).contains(&diff) {
                if diff.abs() > 1000 {
                    next_seq = Some(seq);
                }
                continue;
            }
        }
        next_seq = Some(seq.wrapping_add(1));
        received += 1;

        let Some(frame) = decrypt_rtp(&key, &iv, &packet[..len]) else {
            errors += 1;
            continue;
        };
        match decoder.decode_packet::<i16>(&frame, &mut pcm) {
            Ok(samples) => {
                if decoded == 0 {
                    log::info!(
                        "audio: 首帧解码成功 {} 个样本，帧长 {}",
                        samples.len(),
                        frame.len()
                    );
                }
                audio_out::push_pcm(samples);
                decoded += 1;
                if decoded % 500 == 0 {
                    log::info!(
                        "audio: 已收 {received} 包，解码 {decoded} 帧，队列 {} 样本",
                        audio_out::pending()
                    );
                }
            }
            Err(e) => {
                errors += 1;
                if errors <= 3 {
                    log::warn!("audio: ALAC 解码失败（{e}），帧长 {}", frame.len());
                }
            }
        }
    }
    log::info!("audio: 结束，收 {received} 包，解码 {decoded} 帧，错误 {errors}");
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
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock
                || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(e) => {
                log::warn!("{tag} 接收失败: {e}");
                break;
            }
        }
    }
}

fn drain_tcp(mut stream: TcpStream, tag: &'static str) {
    let mut buf = [0u8; 2048];
    loop {
        match stream.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => log::debug!("{tag} 收到 {n} 字节"),
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock
                || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(e) => {
                log::warn!("{tag} 连接读取失败: {e}");
                break;
            }
        }
    }
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
    let peer_ip = s.peer_addr().ok().map(|a| a.ip());

    // AirPlay 的 RTSP 是长连接：同一条连接上会连续发 fp-setup seq1/seq3、
    // SETUP、GET /info、RECORD……每处理完一个请求必须继续读下一个。
    loop {
        let mut first = String::new();
        if reader.read_line(&mut first)? == 0 {
            break;
        }
        let first = first.trim_end();
        if first.is_empty() {
            continue;
        }
        let mut it = first.split_whitespace();
        let method = it.next().unwrap_or("").to_string();
        let path = it.next().unwrap_or("").to_string();
        let proto = it.next().unwrap_or("RTSP/1.0").to_string();

        let mut len = 0usize;
        let mut cseq = String::from("0");
        let mut close = false;
        loop {
            let mut line = String::new();
            if reader.read_line(&mut line)? == 0 {
                return Ok(());
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
                } else if k.eq_ignore_ascii_case("connection")
                    && v.trim().eq_ignore_ascii_case("close")
                {
                    close = true;
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
            ("GET", "/info") => (
                200,
                "application/x-apple-binary-plist",
                info_plist(&ctx.device_id, &ctx.identity_pub),
            ),
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
                if let Some(secret) = verify.shared_secret() {
                    ctx.audio.lock().unwrap().set_shared_secret(secret);
                }
                if verify.session_key().is_some() {
                    *guard = None;
                }
                (200, "application/octet-stream", out)
            }
            ("POST", "/fp-setup") => {
                // seq3 的 164 字节请求体要留着：SETUP 里的 72 字节
                // FairPlay AES key 需要它才能解密。
                let mut guard = ctx.fp.lock().unwrap();
                let out = guard.handle(&body).unwrap_or_default();
                (200, "application/octet-stream", out)
            }
            ("POST", "/auth-setup") => (403, "text/plain", b"no fairplay".to_vec()),
            ("SETUP", p) if p.starts_with("rtsp://") => {
                let fp = ctx.fp.lock().unwrap();
                let mut audio = ctx.audio.lock().unwrap();
                match audio.handle_setup(&body, &fp, peer_ip) {
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
            // 以下响应形态来自 /tmp/ap3.pcap。
            ("GET_PARAMETER", p) if p.starts_with("rtsp://") => {
                let payload = if body.windows(6).any(|w| w == b"volume") {
                    b"volume: 0.000000\r\n".to_vec()
                } else {
                    Vec::new()
                };
                (200, "text/parameters", payload)
            }
            ("RECORD", _) | ("FLUSH", _) | ("TEARDOWN", _) => {
                if method == "TEARDOWN" {
                    audio_out::clear();
                }
                (200, "application/octet-stream", Vec::new())
            }
            ("SET_PARAMETER", _) => (200, "text/plain", Vec::new()),
            ("POST", "/audioMode") | ("POST", "/feedback") => {
                (200, "application/octet-stream", Vec::new())
            }
            _ => (404, "text/plain", b"not found".to_vec()),
        };

        if session.is_none() && ctx.audio.lock().unwrap().session_active {
            session = Some(SESSION_ID);
        }

        let mut head = format!(
            "{proto} {code} OK\r\nCSeq: {cseq}\r\nAudio-Jack-Status: connected; type=digital\r\n"
        );
        if let Some(id) = session {
            head.push_str(&format!("Session: {id}\r\n"));
        }
        head.push_str(&format!(
            "Content-Type: {ctype}\r\nContent-Length: {}\r\n",
            payload.len()
        ));
        if close || method == "TEARDOWN" {
            head.push_str("Connection: close\r\n");
        }
        head.push_str("\r\n");
        s.write_all(head.as_bytes())?;
        s.write_all(&payload)?;
        s.flush()?;

        if close || method == "TEARDOWN" {
            break;
        }
    }
    Ok(())
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

    // 音频/显示能力：对齐 /tmp/ap3.pcap 里 AirReceiver 的 /info 响应。
    // iPhone 会根据这些字段决定第二次 SETUP 里要哪种音频流。
    let device = String::from_utf8_lossy(device_id).to_string();
    d.insert("macAddress".into(), Value::String(device.clone()));
    d.insert("rmodel".into(), Value::String("AirReceiver3,1".into()));

    let mut latencies = Vec::new();
    for ty in [100i64, 101] {
        let mut lat = Dictionary::new();
        lat.insert("type".into(), Value::Integer(ty.into()));
        lat.insert("inputLatencyMicros".into(), Value::Integer(0i64.into()));
        lat.insert("outputLatencyMicros".into(), Value::Integer(0i64.into()));
        lat.insert("audioType".into(), Value::String("default".into()));
        latencies.push(Value::Dictionary(lat));
    }
    d.insert("audioLatencies".into(), Value::Array(latencies));

    let mut audio_formats = Vec::new();
    for ty in [100i64, 101] {
        let mut fmt = Dictionary::new();
        fmt.insert("type".into(), Value::Integer(ty.into()));
        fmt.insert("audioInputFormats".into(), Value::Integer(67_108_860i64.into()));
        fmt.insert("audioOutputFormats".into(), Value::Integer(67_108_860i64.into()));
        audio_formats.push(Value::Dictionary(fmt));
    }
    d.insert("audioFormats".into(), Value::Array(audio_formats));

    let mut display = Dictionary::new();
    display.insert("uuid".into(), Value::String("e5f7a68d-7b0f-4305-984b-974f677a150b".into()));
    display.insert("widthPhysical".into(), Value::Integer(0i64.into()));
    display.insert("heightPhysical".into(), Value::Integer(0i64.into()));
    display.insert("width".into(), Value::Integer(1920i64.into()));
    display.insert("height".into(), Value::Integer(1080i64.into()));
    display.insert("widthPixels".into(), Value::Integer(1920i64.into()));
    display.insert("heightPixels".into(), Value::Integer(1080i64.into()));
    display.insert("rotation".into(), Value::Boolean(false));
    display.insert("overscanned".into(), Value::Boolean(false));
    display.insert("refreshRate".into(), Value::Real(1.0 / 60.0));
    display.insert("maxFPS".into(), Value::Integer(60i64.into()));
    display.insert("features".into(), Value::Integer(14i64.into()));
    d.insert("displays".into(), Value::Array(vec![Value::Dictionary(display)]));

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
    // 我们自己接收端成功出声时抓到的真实报文（2026-11-16）。
    const OWN_SEQ3: &[u8] =
        include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/own_fp_seq3.bin"));
    const OWN_SETUP1: &[u8] =
        include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/own_setup1.bplist"));
    const OWN_AUDIO_RTP: &[u8] =
        include_bytes!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/own_audio_rtp.bin"));

    fn post(port: u16, path: &str, body: &[u8]) -> Vec<u8> {
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        let head = format!(
            "POST {path} HTTP/1.1\r\nHost: x\r\nConnection: close\r\nContent-Length: {}\r\n\r\n",
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
            "{first_line}\r\nCSeq: 2\r\nConnection: close\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: {}\r\n\r\n",
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
        s.write_all(b"GET /info HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n").unwrap();
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
        let resp = audio.handle_setup(SETUP1, &fp, None).expect("真实 SETUP 应成功");

        assert_eq!(
            audio.key.expect("应保存 AES key").to_vec(),
            unhex("38c9b755988270948b207e9e67ee18ff")
        );
        assert_eq!(
            audio.eiv.expect("应保存 AES IV").to_vec(),
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
        // eventPort 必须是 TCP，iPhone 收到 SETUP 后会立刻连它。
        assert!(TcpStream::connect(("127.0.0.1", audio.event_port)).is_ok());
    }

    #[test]
    fn decodes_real_audio_from_our_own_successful_capture() {
        let mut fp = FpSession::new();
        assert!(fp.handle(OWN_SEQ3).is_some());
        let mut audio = AudioSession::default();
        audio
            .handle_setup(OWN_SETUP1, &fp, None)
            .expect("真实 SETUP1 应成功");

        let key = audio.key.expect("key");
        let iv = audio.eiv.expect("iv");
        assert_eq!(OWN_AUDIO_RTP.len(), 860);
        let frame = decrypt_rtp(&key, &iv, OWN_AUDIO_RTP).expect("RTP 应能解密");
        let info = alac::StreamInfo::from_sdp_format_parameters(&alac_format(352, 262_144))
            .expect("ALAC 参数");
        let mut decoder = alac::Decoder::new(info);
        let mut pcm = vec![0i16; 8192];
        let samples = decoder
            .decode_packet::<i16>(&frame, &mut pcm)
            .expect("真实 ALAC 帧应能解码");
        assert_eq!(samples.len(), 704);
    }

    #[test]
    fn setup_streams_allocates_audio_ports() {
        let fp = FpSession::new();
        let mut audio = AudioSession::default();
        audio.key = Some([7u8; 16]);
        audio.eiv = Some([9u8; 16]);
        let resp = audio
            .handle_setup(SETUP2, &fp, None)
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

    fn read_one(reader: &mut BufReader<TcpStream>) -> (String, Vec<u8>) {
        let mut line = String::new();
        reader.read_line(&mut line).unwrap();
        let mut headers = String::new();
        let mut len = 0usize;
        loop {
            let mut h = String::new();
            reader.read_line(&mut h).unwrap();
            if h == "\r\n" || h.is_empty() {
                break;
            }
            if let Some((k, v)) = h.trim().split_once(':') {
                if k.eq_ignore_ascii_case("content-length") {
                    len = v.trim().parse().unwrap();
                }
            }
            headers.push_str(&h);
        }
        let mut body = vec![0u8; len];
        reader.read_exact(&mut body).unwrap();
        (line + &headers, body)
    }

    #[test]
    fn rtsp_connection_keeps_alive_for_multiple_requests() {
        let port = spawn(0).unwrap();
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        let mut reader = BufReader::new(s.try_clone().unwrap());

        let mut seq1 = b"FPLY".to_vec();
        seq1.extend_from_slice(&[3, 1, 1, 0, 0, 0, 0, 0, 0, 0]);
        seq1.push(2);
        seq1.push(0);
        let head = format!(
            "POST /fp-setup RTSP/1.0\r\nCSeq: 0\r\nContent-Length: 16\r\n\r\n"
        );
        s.write_all(head.as_bytes()).unwrap();
        s.write_all(&seq1).unwrap();
        s.flush().unwrap();
        let (r1, b1) = read_one(&mut reader);
        assert!(r1.contains("200 OK"));
        assert_eq!(b1.len(), 142);

        s.write_all(b"GET /info RTSP/1.0\r\nCSeq: 1\r\n\r\n").unwrap();
        s.flush().unwrap();
        let (r2, b2) = read_one(&mut reader);
        assert!(r2.contains("200 OK"));
        assert!(b2.starts_with(b"bplist00"));
    }

    #[test]
    fn derives_audio_key_with_pairing_secret() {
        use crate::fairplay3::hex;

        let mut audio = AudioSession::default();
        audio.fp_key = Some(std::array::from_fn(|i| i as u8));
        audio.eiv = Some(std::array::from_fn(|i| (0x10 + i) as u8));
        audio.shared_secret = Some(std::array::from_fn(|i| (0x20 + i) as u8));
        audio.derive_key();
        assert_eq!(
            hex(&audio.key.expect("key")),
            "845e82df15aa7c75fa1964c1557f80a8"
        );
    }

    #[test]
    fn rtp_cbc_decrypt_matches_nist_vector() {
        use crate::fairplay3::unhex;

        let key: [u8; 16] = unhex("2b7e151628aed2a6abf7158809cf4f3c").try_into().unwrap();
        let iv: [u8; 16] = unhex("000102030405060708090a0b0c0d0e0f").try_into().unwrap();
        let cipher = unhex("7649abac8119b246cee98e9b12e9197d");
        let plain = unhex("6bc1bee22e409f96e93d7e117393172a");
        let mut packet = vec![0x80, 0x60, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0];
        packet.extend_from_slice(&cipher);
        assert_eq!(decrypt_rtp(&key, &iv, &packet).unwrap(), plain);
    }

    #[test]
    fn alac_format_uses_stream_parameters() {
        assert_eq!(
            alac_format(352, 262_144),
            "352 0 16 40 10 14 2 255 0 0 44100"
        );
        assert_eq!(
            alac_format(352, 1 << 19),
            "352 0 24 40 10 14 2 255 0 0 44100"
        );
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
