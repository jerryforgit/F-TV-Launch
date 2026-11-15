//! SSDP：让手机在「投屏」列表里看到设备（应答 M-SEARCH + 定时 NOTIFY）。

use socket2::{Domain, Protocol, Socket, Type};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::Duration;

pub const GROUP: Ipv4Addr = Ipv4Addr::new(239, 255, 255, 250);
pub const PORT: u16 = 1900;
const DEVICE: &str = "urn:schemas-upnp-org:device:MediaRenderer:1";

fn bind_multicast() -> std::io::Result<UdpSocket> {
    let sock = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    sock.set_reuse_address(true)?;
    sock.set_nonblocking(false)?;
    sock.bind(&SocketAddr::from((Ipv4Addr::UNSPECIFIED, PORT)).into())?;
    sock.join_multicast_v4(&GROUP, &Ipv4Addr::UNSPECIFIED)?;
    Ok(sock.into())
}

/// 后台线程：应答发现请求并定期宣告。
pub fn spawn(ip: String, http_port: u16, udn: String, running: Arc<AtomicBool>) {
    thread::spawn(move || {
        let sock = match bind_multicast() {
            Ok(s) => s,
            Err(e) => {
                log::error!("ssdp bind failed: {e}");
                return;
            }
        };
        let _ = sock.set_read_timeout(Some(Duration::from_secs(1)));
        let location = format!("http://{ip}:{http_port}/desc.xml");
        let mut buf = [0u8; 2048];
        let mut last_notify = std::time::Instant::now() - Duration::from_secs(60);
        while running.load(Ordering::Relaxed) {
            if last_notify.elapsed() >= Duration::from_secs(50) {
                last_notify = std::time::Instant::now();
                for nt in ["upnp:rootdevice", DEVICE] {
                    let msg = notify(&location, &udn, nt);
                    let _ = sock.send_to(msg.as_bytes(), SocketAddr::from((GROUP, PORT)));
                }
            }
            match sock.recv_from(&mut buf) {
                Ok((len, from)) => {
                    let text = String::from_utf8_lossy(&buf[..len]).to_string();
                    if !text.starts_with("M-SEARCH") {
                        continue;
                    }
                    let st = header(&text, "ST").unwrap_or_default();
                    if !(st == "ssdp:all"
                        || st == "upnp:rootdevice"
                        || st == DEVICE
                        || st.contains(&udn))
                    {
                        continue;
                    }
                    let reply = response(&location, &udn, &st);
                    let _ = sock.send_to(reply.as_bytes(), from);
                }
                Err(_) => continue,
            }
        }
    });
}

fn header(msg: &str, name: &str) -> Option<String> {
    msg.lines().find_map(|line| {
        let (k, v) = line.split_once(':')?;
        if k.trim().eq_ignore_ascii_case(name) {
            Some(v.trim().to_string())
        } else {
            None
        }
    })
}

fn response(location: &str, udn: &str, st: &str) -> String {
    let usn = if st == "upnp:rootdevice" {
        format!("uuid:{udn}::upnp:rootdevice")
    } else if st == DEVICE {
        format!("uuid:{udn}::{DEVICE}")
    } else {
        format!("uuid:{udn}")
    };
    format!(
        "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\nLOCATION: {location}\r\n\
         SERVER: Linux/3.0 UPnP/1.0 FTVCast/0.1\r\nST: {st}\r\nUSN: {usn}\r\n\r\n"
    )
}

fn notify(location: &str, udn: &str, nt: &str) -> String {
    format!(
        "NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\n\
         LOCATION: {location}\r\nNT: {nt}\r\nNTS: ssdp:alive\r\n\
         SERVER: Linux/3.0 UPnP/1.0 FTVCast/0.1\r\nUSN: uuid:{udn}::{nt}\r\n\r\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_header() {
        let msg = "M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\nMX: 2\r\n\r\n";
        assert_eq!(header(msg, "ST").as_deref(), Some("ssdp:all"));
    }

    #[test]
    fn builds_response() {
        let r = response("http://1.2.3.4:8090/desc.xml", "ftv-1", DEVICE);
        assert!(r.contains("LOCATION: http://1.2.3.4:8090/desc.xml"));
        assert!(r.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"));
        assert!(r.contains("USN: uuid:ftv-1::urn:schemas-upnp-org:device:MediaRenderer:1"));
    }
}
