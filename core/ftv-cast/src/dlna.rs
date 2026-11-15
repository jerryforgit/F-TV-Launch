//! DLNA (UPnP AV) 接收端：设备描述与 SOAP 动作。
//! 端口/HTTP 层稍后补，这里先把协议内容做成可单测的纯函数。

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
            "<service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>",
            "<controlURL>/control/avt</controlURL><eventSubURL>/event/avt</eventSubURL>",
            "<SCPDURL>/scpd/avt.xml</SCPDURL></service>",
            "<service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>",
            "<controlURL>/control/rcs</controlURL><eventSubURL>/event/rcs</eventSubURL>",
            "<SCPDURL>/scpd/rcs.xml</SCPDURL></service>",
            "<service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>",
            "<serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>",
            "<controlURL>/control/cms</controlURL><eventSubURL>/event/cms</eventSubURL>",
            "<SCPDURL>/scpd/cms.xml</SCPDURL></service>",
            "</serviceList></device></root>"
        ),
        friendly, udn
    )
}

/// 秒数 -> UPnP 时间格式 `HH:MM:SS`。
pub fn time(seconds: u64) -> String {
    format!("{:02}:{:02}:{:02}", seconds / 3600, (seconds / 60) % 60, seconds % 60)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn description_contains_renderer() {
        let xml = device_description("ftv-1", "F TV 桌面");
        assert!(xml.contains("MediaRenderer:1"));
        assert!(xml.contains("F TV 桌面"));
        assert!(xml.contains("<UDN>uuid:ftv-1</UDN>"));
    }

    #[test]
    fn formats_time() {
        assert_eq!(time(0), "00:00:00");
        assert_eq!(time(3671), "01:01:11");
    }
}
