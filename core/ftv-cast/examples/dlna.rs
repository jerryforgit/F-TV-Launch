//! 桌面端验证：跑一个 DLNA 接收端，播放请求只打日志。
use ftv_cast::dlna::{self, Shared, Sink, State};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};
use std::time::Duration;

struct LogSink;
impl Sink for LogSink {
    fn play(&self, url: &str, title: &str) {
        println!("PLAY url={url} title={title}");
    }
    fn stop(&self) {
        println!("STOP");
    }
    fn seek(&self, s: u64) {
        println!("SEEK {s}s");
    }
    fn pause(&self) {
        println!("PAUSE");
    }
    fn resume(&self) {
        println!("RESUME");
    }
    fn set_volume(&self, v: u8) {
        println!("VOLUME {v}");
    }
}

fn local_ip() -> String {
    std::net::UdpSocket::bind("0.0.0.0:0")
        .and_then(|s| {
            s.connect("8.8.8.8:80")?;
            Ok(s.local_addr()?.ip().to_string())
        })
        .unwrap_or_else(|_| "127.0.0.1".into())
}

fn main() {
    let ip = local_ip();
    let port: u16 = std::env::args()
        .nth(1)
        .and_then(|s| s.parse().ok())
        .unwrap_or(8091);
    let state: Shared = Arc::new(Mutex::new(State::new()));
    let running = Arc::new(AtomicBool::new(true));
    let udn = "ftv-rust-1".to_string();
    ftv_cast::ssdp::spawn(ip.clone(), port, udn.clone(), running.clone());
    ftv_cast::http::spawn(
        port,
        udn,
        "F TV 桌面 (Rust)".into(),
        state,
        Arc::new(LogSink),
        running,
    )
    .expect("http bind");
    println!("DLNA listening on http://{ip}:{port}/desc.xml");
    std::thread::sleep(Duration::from_secs(600));
}
