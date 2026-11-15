//! 极简 HTTP：设备描述、SCPD、SOAP 控制。

use crate::dlna::{self, Shared, Sink};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;

pub fn spawn(port: u16, udn: String, friendly: String, state: Shared, sink: Arc<dyn Sink>, running: Arc<AtomicBool>) -> std::io::Result<()> {
    let listener = TcpListener::bind(("0.0.0.0", port))?;
    thread::spawn(move || {
        for stream in listener.incoming() {
            if !running.load(Ordering::Relaxed) {
                break;
            }
            if let Ok(s) = stream {
                let (udn, friendly, state, sink) = (udn.clone(), friendly.clone(), state.clone(), sink.clone());
                thread::spawn(move || {
                    let _ = handle(s, &udn, &friendly, &state, sink.as_ref());
                });
            }
        }
    });
    Ok(())
}

fn handle(mut s: TcpStream, udn: &str, friendly: &str, state: &Shared, sink: &dyn Sink) -> std::io::Result<()> {
    let mut reader = BufReader::new(s.try_clone()?);
    let mut first = String::new();
    reader.read_line(&mut first)?;
    let mut parts = first.split_whitespace();
    let method = parts.next().unwrap_or("");
    let path = parts.next().unwrap_or("").to_string();

    let mut len = 0usize;
    let mut soap_action = String::new();
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
            } else if k.eq_ignore_ascii_case("soapaction") {
                soap_action = v.trim().trim_matches('"').to_string();
            }
        }
    }
    let mut body = vec![0u8; len];
    if len > 0 {
        reader.read_exact(&mut body)?;
    }
    let body = String::from_utf8_lossy(&body).to_string();

    let (code, ctype, payload) = match (method, path.as_str()) {
        ("GET", "/desc.xml") => (200, "text/xml; charset=utf-8", dlna::device_description(udn, friendly)),
        ("GET", p) if p.starts_with("/scpd/") => (200, "text/xml; charset=utf-8", scpd()),
        ("POST", p) if p.starts_with("/control/") => {
            let (service, action) = soap_action.split_once('#').unwrap_or(("", ""));
            let service_type = match service {
                s if s.ends_with("AVTransport:1") => dlna::AVT,
                s if s.ends_with("RenderingControl:1") => dlna::RCS,
                s if s.ends_with("ConnectionManager:1") => dlna::CMS,
                _ => "",
            };
            (200, "text/xml; charset=utf-8", dlna::handle_soap(service_type, action, &body, state, sink))
        }
        ("SUBSCRIBE", _) => (200, "text/xml; charset=utf-8", String::new()),
        _ => (404, "text/plain", "not found".into()),
    };

    let head = format!(
        "HTTP/1.1 {code} OK\r\nContent-Type: {ctype}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        payload.len()
    );
    s.write_all(head.as_bytes())?;
    s.write_all(payload.as_bytes())?;
    s.flush()
}

fn scpd() -> String {
    "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">\
     <specVersion><major>1</major><minor>0</minor></specVersion><actionList/>\
     <serviceStateTable/></scpd>"
        .into()
}
