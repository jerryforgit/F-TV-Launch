//! TLV8 编解码（HAP 的 pair-setup / pair-verify 用的报文格式）。
//!
//! 每个元素是 `<type:1><len:1><value:len>`；type 相同且相邻的段需要拼接。

pub const STATE: u8 = 0x06;
pub const METHOD: u8 = 0x00;
pub const FLAGS: u8 = 0x13;
pub const PUBLIC_KEY: u8 = 0x03;
pub const PROOF: u8 = 0x04;
pub const ENCRYPTED_DATA: u8 = 0x05;
pub const IDENTIFIER: u8 = 0x01;
pub const SIGNATURE: u8 = 0x0a;
pub const ERROR: u8 = 0x07;

pub fn encode(items: &[(u8, &[u8])]) -> Vec<u8> {
    let mut out = Vec::new();
    for (ty, value) in items {
        if value.is_empty() {
            out.push(*ty);
            out.push(0);
            continue;
        }
        let mut off = 0;
        while off < value.len() {
            let len = std::cmp::min(255, value.len() - off);
            out.push(*ty);
            out.push(len as u8);
            out.extend_from_slice(&value[off..off + len]);
            off += len;
        }
    }
    out
}

pub fn decode(data: &[u8]) -> Vec<(u8, Vec<u8>)> {
    let mut out: Vec<(u8, Vec<u8>)> = Vec::new();
    let mut i = 0;
    while i + 1 < data.len() {
        let ty = data[i];
        let len = data[i + 1] as usize;
        i += 2;
        if i + len > data.len() {
            break;
        }
        let value = &data[i..i + len];
        i += len;
        // 相邻同类型要拼接（长值会被拆段）
        if let Some(last) = out.last_mut() {
            if last.0 == ty {
                last.1.extend_from_slice(value);
                continue;
            }
        }
        out.push((ty, value.to_vec()));
    }
    out
}

pub fn get(items: &[(u8, Vec<u8>)], ty: u8) -> Option<&[u8]> {
    items.iter().find(|(t, _)| *t == ty).map(|(_, v)| v.as_slice())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_simple() {
        let data = encode(&[(STATE, &[1]), (METHOD, &[0])]);
        let items = decode(&data);
        assert_eq!(get(&items, STATE), Some(&[1u8][..]));
        assert_eq!(get(&items, METHOD), Some(&[0u8][..]));
    }

    #[test]
    fn splits_and_joins_long_values() {
        let big = vec![7u8; 300];
        let data = encode(&[(PUBLIC_KEY, &big)]);
        assert_eq!(data[1], 255);
        assert_eq!(data[257], PUBLIC_KEY); // 第二段重新写 type
        assert_eq!(data[258], 45);
        let items = decode(&data);
        assert_eq!(get(&items, PUBLIC_KEY).unwrap().len(), 300);
    }

    #[test]
    fn handles_empty_and_truncated() {
        assert!(decode(&[]).is_empty());
        assert!(decode(&[STATE]).is_empty());
        let items = decode(&[STATE, 3, 1, 2]);
        assert!(items.is_empty());
    }
}
