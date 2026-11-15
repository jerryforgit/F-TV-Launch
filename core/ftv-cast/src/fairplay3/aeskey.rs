//! `decryptAESKey`：FairPlay 3 第二阶段的最终出口。
//!
//! 输入是 164 字节 FPLY 数据块和 72 字节 AES key 密文；输出 16 字节 key。
//! 步骤与参考实现一致：SAP key → 轮密钥 → Z-key 异或 → cycle → chunk1 异或 →
//! X-key 异或 → Z-key 异或。

use super::session::{cycle, generate_key_schedule, generate_session_key};
use super::tables::{DEFAULT_SAP, X_KEY, Z_KEY};

#[inline]
fn xor16(a: &[u8], b: &[u8]) -> [u8; 16] {
    let mut out = [0u8; 16];
    for i in 0..16 {
        out[i] = a[i] ^ b[i];
    }
    out
}

/// 解密 FairPlay 3 的 AES key。长度不符合参考实现的断言时返回 `None`。
///
/// 长度要求：`message3` 164 字节，`cipher_text` 72 字节。
/// `/fp-setup` 第二阶段可以直接使用这个函数；后续接端点时不必再暴露中间步骤。
pub fn decrypt_aes_key(message3: &[u8], cipher_text: &[u8]) -> Option<[u8; 16]> {
    if message3.len() != 164 || cipher_text.len() != 72 {
        return None;
    }

    let chunk1 = &cipher_text[16..32];
    let chunk2 = &cipher_text[56..72];

    let sap_key = generate_session_key(&DEFAULT_SAP, message3);
    let key_schedule = generate_key_schedule(&sap_key);

    let block_in = xor16(chunk2, &Z_KEY);
    let cycled = cycle(&block_in, &key_schedule);

    let mut key_out = [0u8; 16];
    for i in 0..16 {
        key_out[i] = cycled[i] ^ chunk1[i];
    }

    let key_out = xor16(&key_out, &X_KEY);
    Some(xor16(&key_out, &Z_KEY))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fairplay3::test_vectors::AES_CASES;
    use crate::fairplay3::{hex, unhex};

    #[test]
    fn decrypt_aes_key_matches_python_with_intermediates() {
        assert!(!AES_CASES.is_empty());
        for case in AES_CASES {
            let message = unhex(case.message);
            let cipher = unhex(case.cipher);
            assert_eq!(message.len(), 164);
            assert_eq!(cipher.len(), 72);

            let sap_key = generate_session_key(&DEFAULT_SAP, &message);
            assert_eq!(hex(&sap_key), case.sap_key, "SAP key 不一致: {}", case.name);

            let schedule = generate_key_schedule(&sap_key);
            let flat: Vec<u32> = schedule.iter().flatten().copied().collect();
            assert_eq!(flat.len(), 44);
            for (i, (got, expected)) in flat.iter().zip(case.schedule.iter()).enumerate() {
                assert_eq!(got, expected, "轮密钥第 {i} 个 u32 不一致: {}", case.name);
            }

            let z_block = xor16(&cipher[56..72], &Z_KEY);
            assert_eq!(hex(&z_block), case.z_block, "Z-key 异或结果不一致: {}", case.name);

            let cycled = cycle(&z_block, &schedule);
            assert_eq!(hex(&cycled), case.cycled, "cycle 结果不一致: {}", case.name);

            let mut after_chunk1 = [0u8; 16];
            for i in 0..16 {
                after_chunk1[i] = cycled[i] ^ cipher[16 + i];
            }
            assert_eq!(
                hex(&after_chunk1),
                case.after_chunk1,
                "chunk1 异或结果不一致: {}",
                case.name
            );

            let after_x = xor16(&after_chunk1, &X_KEY);
            assert_eq!(hex(&after_x), case.after_x, "X-key 异或结果不一致: {}", case.name);

            let final_key = xor16(&after_x, &Z_KEY);
            assert_eq!(hex(&final_key), case.final_key, "最终 key 不一致: {}", case.name);

            let public = decrypt_aes_key(&message, &cipher).expect("长度合法");
            assert_eq!(public, final_key, "decryptAESKey 公开接口不一致: {}", case.name);
        }
    }

    #[test]
    fn rejects_wrong_lengths() {
        assert!(decrypt_aes_key(&vec![0u8; 163], &vec![0u8; 72]).is_none());
        assert!(decrypt_aes_key(&vec![0u8; 164], &vec![0u8; 71]).is_none());
        assert!(decrypt_aes_key(&vec![0u8; 164], &vec![0u8; 72]).is_some());
    }

    /// 真实抓包（/tmp/ap3.pcap）：iPhone -> AirReceiver 的 /fp-setup seq3
    /// 与随后 SETUP 里的 ekey/eiv。密钥由 Python 参考实现算出。
    #[test]
    fn decrypts_real_airplay2_capture() {
        let message3 = unhex(
            "46504c590301030000000098028f1a9c539c6a88f2c21187e5058607cca1ff770a75c5\
             985940c44e702b56cc67c16822cc729efe9231398a7583f9dff9c724b2ed4098b48e50\
             7bddf35b9705942a0f1fb619838adbb16a232b5aa6cbf420f76448ceeff3ad1a5884a6\
             26ac256990f3e0c43756df04085fffe2ec8ddfc481c57d22cfc022423214b1187dbf24\
             851eff881c83ce00fb1145dee759df18884551f249cbe83c",
        );
        let ekey = unhex(
            "46504c59010201000000003c000000002599637c0d208f72c4d42f25bcac2bc7000000\
             10653d25dc9e8ad48bf8a6e2f91ae575f3ad0ed60318ec306b30a4a0c125ba31096509\
             d0d0",
        );
        assert_eq!(message3.len(), 164);
        assert_eq!(ekey.len(), 72);
        assert_eq!(
            hex(&decrypt_aes_key(&message3, &ekey).unwrap()),
            "38c9b755988270948b207e9e67ee18ff"
        );
    }
}
