//! 极简 mDNS 记录编码（RFC 6763）：AirPlay 的 `pk` 超过 255 字节，系统库发不了，
//! 所以自己编码 TXT 并拆段。

/// 把 key/value 编成 mDNS TXT rdata；单段超过 255 字节时按 RFC 6763 拆成多段。
pub fn txt_rdata(entries: &[(&str, &str)]) -> Vec<u8> {
    let mut out = Vec::new();
    for (k, v) in entries {
        let full = format!("{}={}", k, v);
        let bytes = full.as_bytes();
        let mut off = 0;
        while off < bytes.len() {
            let len = std::cmp::min(255, bytes.len() - off);
            out.push(len as u8);
            out.extend_from_slice(&bytes[off..off + len]);
            off += len;
        }
    }
    out
}

/// DNS 名字编码（label 长度 + 内容 + 0）。
pub fn encode_name(name: &str) -> Vec<u8> {
    let mut out = Vec::new();
    for label in name.split('.') {
        if label.is_empty() {
            continue;
        }
        let b = label.as_bytes();
        out.push(b.len() as u8);
        out.extend_from_slice(b);
    }
    out.push(0);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_long_txt_value() {
        let pk = "a".repeat(512);
        let rdata = txt_rdata(&[("pk", &pk)]);
        // "pk=" + 512 chars -> 3 段（255 + 255 + 5）
        assert_eq!(rdata[0], 255);
        assert_eq!(rdata[256], 255);
        assert_eq!(rdata.len(), 3 + 515);
        assert_eq!(rdata[512], 5);
    }

    #[test]
    fn encodes_name() {
        assert_eq!(encode_name("ftvbox.local"), b"\x06ftvbox\x05local\x00");
    }
}
