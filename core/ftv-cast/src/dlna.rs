//! DLNA (UPnP AV) 接收端：设备描述、状态机与 SOAP 动作。
//! 这里的函数都是纯逻辑，方便单测；网络部分在 `http` / `ssdp`。

use std::sync::{Arc, Mutex};

pub const AVT: &str = "urn:schemas-upnp-org:service:AVTransport:1";
pub const RCS: &str = "urn:schemas-upnp-org:service:RenderingControl:1";
pub const CMS: &str = "urn:schemas-upnp-org:service:ConnectionManager:1";

/// 平台侧的播放器回调（Android: 拉起播放页；桌面: 本地播放）。
pub trait Sink: Send + Sync {
    fn play(&self, url: &str, title: &str);
    fn stop(&self);
    fn seek(&self, seconds: u64);
    fn pause(&self);
    fn resume(&self);
    fn set_volume(&self, volume: u8);
}

#[derive(Debug, Default)]
pub struct State {
    pub uri: String,
    pub title: String,
    pub transport: String,
    pub duration: u64,
    pub position: u64,
    pub volume: u8,
}

impl State {
    pub fn new() -> Self {
        Self {
            transport: "NO_MEDIA_PRESENT".into(),
            volume: 50,
            ..Default::default()
        }
    }
}

pub type Shared = Arc<Mutex<State>>;

/// 设备描述 XML。
pub fn device_description(udn: &str, friendly: &str) -> String {
    format!(
        concat!(
            "<?xml version=\"1.0\"?>",
            "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" ",
            "xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">",
            "<specVersion><major>1</major><minor>0</minor></specVersion>",
            "<device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>",
            "<friendlyName>{}</friendlyName>",
            "<manufacturer>F TV Launcher</manufacturer>",
            "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>",
            "<dlna:X_DLNACAP>audio-upload,image-upload</dlna:X_DLNACAP>",
            "<modelName>F TV Launcher</modelName>",
            "<UDN>uuid:{}</UDN>",
            "<serviceList>",
            "<service><serviceType>{}</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>",
            "<controlURL>/control/avt</controlURL><eventSubURL>/event/avt</eventSubURL>",
            "<SCPDURL>/scpd/avt.xml</SCPDURL></service>",
            "<service><serviceType>{}</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>",
            "<controlURL>/control/rcs</controlURL><eventSubURL>/event/rcs</eventSubURL>",
            "<SCPDURL>/scpd/rcs.xml</SCPDURL></service>",
            "<service><serviceType>{}</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>",
            "<controlURL>/control/cms</controlURL><eventSubURL>/event/cms</eventSubURL>",
            "<SCPDURL>/scpd/cms.xml</SCPDURL></service>",
            "</serviceList></device></root>"
        ),
        friendly, udn, AVT, RCS, CMS
    )
}

/// 秒数 -> UPnP 时间格式 `HH:MM:SS`。
pub fn time(seconds: u64) -> String {
    format!(
        "{:02}:{:02}:{:02}",
        seconds / 3600,
        (seconds / 60) % 60,
        seconds % 60
    )
}

pub fn parse_time(text: &str) -> u64 {
    let parts: Vec<&str> = text.trim().split(':').collect();
    let num = |s: &str| s.trim().parse::<f64>().unwrap_or(0.0);
    match parts.len() {
        3 => (num(parts[0]) * 3600.0 + num(parts[1]) * 60.0 + num(parts[2])) as u64,
        2 => (num(parts[0]) * 60.0 + num(parts[1])) as u64,
        1 => num(parts[0]) as u64,
        _ => 0,
    }
}

/// SOAP envelope 包裹。
pub fn envelope(service: &str, action: &str, inner: &str) -> String {
    format!(
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" \
         s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>\
         <u:{action}Response xmlns:u=\"{service}\">{inner}</u:{action}Response></s:Body></s:Envelope>"
    )
}

pub fn fault() -> String {
    "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">\
     <s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>\
     <detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>401</errorCode>\
     <errorDescription>Invalid Action</errorDescription></UPnPError></detail></s:Fault></s:Body>\
     </s:Envelope>"
        .into()
}

/// 从 SOAP body 里取 `<Name>value</Name>`。
pub fn arg<'a>(body: &'a str, name: &str) -> String {
    let open = format!("<{name}>");
    let close = format!("</{name}>");
    match (body.find(&open), body.find(&close)) {
        (Some(s), Some(e)) if e > s => body[s + open.len()..e].to_string(),
        _ => String::new(),
    }
}

fn xml_escape(s: &str) -> String {
    s.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

fn xml_unescape(s: &str) -> String {
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
}

/// 处理一个 SOAP 请求，返回响应 XML。
pub fn handle_soap(service: &str, action: &str, body: &str, state: &Shared, sink: &dyn Sink) -> String {
    let mut st = state.lock().unwrap();
    match (service, action) {
        (AVT, "SetAVTransportURI") => {
            st.uri = xml_unescape(&arg(body, "CurrentURI"));
            let meta = xml_unescape(&arg(body, "CurrentURIMetaData"));
            st.title = meta
                .find("<dc:title>")
                .and_then(|s| {
                    let start = s + 10;
                    meta[start..].find("</dc:title>").map(|e| meta[start..start + e].to_string())
                })
                .unwrap_or_default();
            st.transport = "STOPPED".into();
            st.position = 0;
            st.duration = 0;
            let (uri, title) = (st.uri.clone(), st.title.clone());
            drop(st);
            sink.play(&uri, &title);
            envelope(service, action, "")
        }
        (AVT, "Play") => {
            st.transport = "PLAYING".into();
            drop(st);
            sink.resume();
            envelope(service, action, "")
        }
        (AVT, "Pause") => {
            st.transport = "PAUSED_PLAYBACK".into();
            drop(st);
            sink.pause();
            envelope(service, action, "")
        }
        (AVT, "Stop") => {
            st.transport = "STOPPED".into();
            drop(st);
            sink.stop();
            envelope(service, action, "")
        }
        (AVT, "Seek") => {
            let target = parse_time(&arg(body, "Target"));
            st.position = target;
            drop(st);
            sink.seek(target);
            envelope(service, action, "")
        }
        (AVT, "GetTransportInfo") => {
            let t = st.transport.clone();
            envelope(
                service,
                action,
                &format!(
                    "<CurrentTransportState>{t}</CurrentTransportState>\
                     <CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"
                ),
            )
        }
        (AVT, "GetPositionInfo") => {
            let inner = format!(
                "<Track>1</Track><TrackDuration>{}</TrackDuration>\
                 <TrackMetaData>{}</TrackMetaData><TrackURI>{}</TrackURI>\
                 <RelTime>{}</RelTime><AbsTime>{}</AbsTime>\
                 <RelCount>0</RelCount><AbsCount>2147483647</AbsCount>",
                time(st.duration),
                xml_escape(&st.title),
                xml_escape(&st.uri),
                time(st.position),
                time(st.position)
            );
            envelope(service, action, &inner)
        }
        (AVT, "GetMediaInfo") => {
            let inner = format!(
                "<NrTracks>1</NrTracks><MediaDuration>{}</MediaDuration>\
                 <CurrentURI>{}</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>\
                 <NextURI></NextURI><NextURIMetaData></NextURIMetaData>\
                 <PlayMedium>NETWORK</PlayMedium><RecordMedium>NOT_IMPLEMENTED</RecordMedium>\
                 <WriteStatus>NOT_IMPLEMENTED</WriteStatus>",
                time(st.duration),
                xml_escape(&st.uri)
            );
            envelope(service, action, &inner)
        }
        (AVT, "GetDeviceCapabilities") => envelope(
            service,
            action,
            "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia>\
             <RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>",
        ),
        (AVT, "GetTransportSettings") => envelope(
            service,
            action,
            "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>",
        ),
        (RCS, "GetVolume") => envelope(
            service,
            action,
            &format!("<CurrentVolume>{}</CurrentVolume>", st.volume),
        ),
        (RCS, "SetVolume") => {
            let v = arg(body, "DesiredVolume").parse::<u8>().unwrap_or(st.volume);
            st.volume = v.min(100);
            drop(st);
            sink.set_volume(v.min(100));
            envelope(service, action, "")
        }
        (RCS, "GetMute") => envelope(service, action, "<CurrentMute>0</CurrentMute>"),
        (RCS, "SetMute") => envelope(service, action, ""),
        (CMS, "GetProtocolInfo") => envelope(
            service,
            action,
            "<Source></Source><Sink>http-get:*:video/mp4:*,http-get:*:video/x-matroska:*,\
             http-get:*:video/mpeg:*,http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,\
             http-get:*:image/jpeg:*,http-get:*:image/png:*</Sink>",
        ),
        (CMS, "GetCurrentConnectionIDs") => {
            envelope(service, action, "<ConnectionIDs>0</ConnectionIDs>")
        }
        (CMS, "GetCurrentConnectionInfo") => envelope(
            service,
            action,
            "<RcsID>0</RcsID><AVTransportID>0</AVTransportID><ProtocolInfo></ProtocolInfo>\
             <PeerConnectionManager></PeerConnectionManager><PeerConnectionID>-1</PeerConnectionID>\
             <Direction>Input</Direction><Status>OK</Status>",
        ),
        _ => fault(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct NullSink;
    impl Sink for NullSink {
        fn play(&self, _u: &str, _t: &str) {}
        fn stop(&self) {}
        fn seek(&self, _s: u64) {}
        fn pause(&self) {}
        fn resume(&self) {}
        fn set_volume(&self, _v: u8) {}
    }

    #[test]
    fn description_contains_renderer() {
        let xml = device_description("ftv-1", "F TV 桌面");
        assert!(xml.contains("MediaRenderer:1"));
        assert!(xml.contains("<UDN>uuid:ftv-1</UDN>"));
    }

    #[test]
    fn formats_time() {
        assert_eq!(time(0), "00:00:00");
        assert_eq!(time(3671), "01:01:11");
        assert_eq!(parse_time("00:01:05"), 65);
    }

    #[test]
    fn set_uri_and_play() {
        let state: Shared = Arc::new(Mutex::new(State::new()));
        let sink = NullSink;
        let body = "<InstanceID>0</InstanceID><CurrentURI>http://x/a.mp4</CurrentURI>\
                    <CurrentURIMetaData>&lt;dc:title&gt;片子&lt;/dc:title&gt;</CurrentURIMetaData>";
        handle_soap(AVT, "SetAVTransportURI", body, &state, &sink);
        {
            let st = state.lock().unwrap();
            assert_eq!(st.uri, "http://x/a.mp4");
            assert_eq!(st.title, "片子");
        }
        let xml = handle_soap(AVT, "Play", "", &state, &sink);
        assert!(xml.contains("PlayResponse"));
        assert_eq!(state.lock().unwrap().transport, "PLAYING");
    }

    #[test]
    fn volume_clamps() {
        let state: Shared = Arc::new(Mutex::new(State::new()));
        let sink = NullSink;
        handle_soap(RCS, "SetVolume", "<DesiredVolume>150</DesiredVolume>", &state, &sink);
        assert_eq!(state.lock().unwrap().volume, 100);
        let xml = handle_soap(RCS, "GetVolume", "", &state, &sink);
        assert!(xml.contains("<CurrentVolume>100</CurrentVolume>"));
    }
}
