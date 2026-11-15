//! FairPlay 3 的完整移植（`ap2/fairplay3.py` → Rust）。
//!
//! AirPlay 2 的 `/fp-setup` 第二阶段需要：
//! 1. 用 [`generate_session_key`] 从 FPLY 数据块推出 SAP key；
//! 2. 用 [`generate_key_schedule`] / [`cycle`]（类 AES 的查表变换）解密 72 字节的
//!    AES key 密文；
//! 3. 对结果做 X/Z key 的收尾异或，得到音频流真正使用的 key。
//!
//! 本模块的所有实现都以参考 Python 版为准：字节序按 `array.array('I')` 的小端解释，
//! 中间运算使用 i64，避免 Python 任意精度整数与 Rust `u8` 溢出语义不一致。
//! 对照向量由 `scripts/fairplay3_export.py` 从 Python 版直接跑出，测试覆盖每个函数的
//! 中间值与最终输出。

mod aeskey;
mod garble;
mod md5;
mod saphash;
mod session;

pub mod tables;

#[cfg(test)]
pub(crate) mod test_vectors;

pub use aeskey::decrypt_aes_key;
pub use md5::modified_md5;
pub use saphash::saphash;
pub use session::{cycle, decrypt_message, generate_key_schedule, generate_session_key};

/// `/fp-setup` seq3 请求体长度（后续解密 AES key 的 `message3`）。
pub const FP_KEY_MSG_LEN: usize = 164;

#[cfg(test)]
pub(crate) fn unhex(s: &str) -> Vec<u8> {
    assert!(s.len() % 2 == 0, "十六进制串长度必须为偶数");
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16).expect("非法十六进制"))
        .collect()
}

#[cfg(test)]
pub(crate) fn unhex16(s: &str) -> [u8; 16] {
    let v = unhex(s);
    assert_eq!(v.len(), 16, "期望 16 字节，实际 {}", v.len());
    v.try_into().unwrap()
}

#[cfg(test)]
pub(crate) fn hex(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        use std::fmt::Write;
        let _ = write!(out, "{b:02x}");
    }
    out
}
