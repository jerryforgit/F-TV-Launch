//! `decryptMessage` / `generate_key_schedule` / `cycle` / `generate_session_key`。
//!
//! 这几段是 FairPlay 3 的核心变换，`decryptAESKey` 依赖它们。参考实现里大量
//! 使用 `array.array('I')`（本机小端）和固定长度的表，这里统一按小端解释。

use super::md5::modified_md5;
use super::saphash::saphash;
use super::tables::{
    INDEX_MANGLE, INITIAL_SESSION_KEY, MESSAGE_IV, MESSAGE_KEY, STATIC_SOURCE_1, STATIC_SOURCE_2,
    TABLE_S1, TABLE_S10, TABLE_S2, TABLE_S3, TABLE_S4, TABLE_S5, TABLE_S6, TABLE_S7, TABLE_S8,
    TABLE_S9, T_KEY,
};

#[inline]
fn xor16(a: &[u8], b: &[u8]) -> [u8; 16] {
    let mut out = [0u8; 16];
    for i in 0..16 {
        out[i] = a[i] ^ b[i];
    }
    out
}

#[inline]
fn le_words16(bytes: &[u8]) -> [u32; 4] {
    let mut words = [0u32; 4];
    for (i, word) in words.iter_mut().enumerate() {
        *word = u32::from_le_bytes([
            bytes[i * 4],
            bytes[i * 4 + 1],
            bytes[i * 4 + 2],
            bytes[i * 4 + 3],
        ]);
    }
    words
}

#[inline]
fn words_to_bytes16(words: &[u32; 4]) -> [u8; 16] {
    let mut out = [0u8; 16];
    for (i, word) in words.iter().enumerate() {
        out[i * 4..i * 4 + 4].copy_from_slice(&word.to_le_bytes());
    }
    out
}

#[inline]
fn message_table(index: usize, byte: u8) -> u8 {
    TABLE_S2[((97 * index) % 144) * 256 + byte as usize]
}

#[inline]
fn key_table(index: usize, byte: u8) -> u8 {
    TABLE_S1[((31 * index) % 0x28) * 256 + byte as usize]
}

#[inline]
fn permute_table2(index: usize, byte: u8) -> u8 {
    TABLE_S4[((71 * index) % 144) * 256 + byte as usize]
}

/// 参考实现 `Fairplay3.decryptMessage`：输入 FPLY 数据块（至少 144 字节），
/// 按第 12 字节的 mode 解出 128 字节明文。
pub fn decrypt_message(message_in: &[u8]) -> [u8; 128] {
    decrypt_message_impl(message_in, None)
}

#[cfg(test)]
pub(crate) fn decrypt_message_traced(message_in: &[u8]) -> ([u8; 128], Vec<(u8, u8, [u8; 16])>) {
    let mut trace = Vec::new();
    let out = decrypt_message_impl(message_in, Some(&mut trace));
    (out, trace)
}

fn decrypt_message_impl(
    message_in: &[u8],
    mut trace: Option<&mut Vec<(u8, u8, [u8; 16])>>,
) -> [u8; 128] {
    assert!(
        message_in.len() >= 144,
        "decryptMessage 需要至少 144 字节输入"
    );
    let mode = message_in[12] as usize;
    assert!(mode <= 3, "未知的 FairPlay mode: {mode}");

    let mut decrypted = [0u8; 128];
    let key = &MESSAGE_KEY[mode];

    for i in 0..8usize {
        let mut buffer = [0u8; 16];
        for j in 0..16 {
            buffer[j] = if mode == 3 {
                message_in[(0x80 - 0x10 * i) + j]
            } else {
                message_in[0x10 * (i + 1) + j]
            };
        }
        if let Some(t) = trace.as_mut() {
            t.push((i as u8, 0, buffer));
        }

        for j in 0..9usize {
            let base = 0x80 - 0x10 * j;

            buffer[0x0] = message_table(base, buffer[0x0]) ^ key[base];
            buffer[0x4] = message_table(base + 0x4, buffer[0x4]) ^ key[base + 0x4];
            buffer[0x8] = message_table(base + 0x8, buffer[0x8]) ^ key[base + 0x8];
            buffer[0xc] = message_table(base + 0xc, buffer[0xc]) ^ key[base + 0xc];

            let tmp = buffer[0x0d];
            buffer[0xd] = message_table(base + 0xd, buffer[0x9]) ^ key[base + 0xd];
            buffer[0x9] = message_table(base + 0x9, buffer[0x5]) ^ key[base + 0x9];
            buffer[0x5] = message_table(base + 0x5, buffer[0x1]) ^ key[base + 0x5];
            buffer[0x1] = message_table(base + 0x1, tmp) ^ key[base + 0x1];

            let tmp = buffer[0x02];
            buffer[0x2] = message_table(base + 0x2, buffer[0xa]) ^ key[base + 0x2];
            buffer[0xa] = message_table(base + 0xa, tmp) ^ key[base + 0xa];
            let tmp = buffer[0x06];
            buffer[0x6] = message_table(base + 0x6, buffer[0xe]) ^ key[base + 0x6];
            buffer[0xe] = message_table(base + 0xe, tmp) ^ key[base + 0xe];

            let tmp = buffer[0x3];
            buffer[0x3] = message_table(base + 0x3, buffer[0x7]) ^ key[base + 0x3];
            buffer[0x7] = message_table(base + 0x7, buffer[0xb]) ^ key[base + 0x7];
            buffer[0xb] = message_table(base + 0xb, buffer[0xf]) ^ key[base + 0xb];
            buffer[0xf] = message_table(base + 0xf, tmp) ^ key[base + 0xf];

            if let Some(t) = trace.as_mut() {
                t.push((i as u8, j as u8 + 1, buffer));
            }

            // 参考实现把 table_s9 的 4 个 dword 变换放在 j 循环内部：
            // 每轮结束后 buffer 被整体替换，下一轮从替换后的值继续。
            let mut block = [0u32; 4];
            block[0] = TABLE_S9[0x000 + buffer[0x0] as usize]
                ^ TABLE_S9[0x100 + buffer[0x1] as usize]
                ^ TABLE_S9[0x200 + buffer[0x2] as usize]
                ^ TABLE_S9[0x300 + buffer[0x3] as usize];
            block[1] = TABLE_S9[0x000 + buffer[0x4] as usize]
                ^ TABLE_S9[0x100 + buffer[0x5] as usize]
                ^ TABLE_S9[0x200 + buffer[0x6] as usize]
                ^ TABLE_S9[0x300 + buffer[0x7] as usize];
            block[2] = TABLE_S9[0x000 + buffer[0x8] as usize]
                ^ TABLE_S9[0x100 + buffer[0x9] as usize]
                ^ TABLE_S9[0x200 + buffer[0xA] as usize]
                ^ TABLE_S9[0x300 + buffer[0xB] as usize];
            block[3] = TABLE_S9[0x000 + buffer[0xC] as usize]
                ^ TABLE_S9[0x100 + buffer[0xD] as usize]
                ^ TABLE_S9[0x200 + buffer[0xE] as usize]
                ^ TABLE_S9[0x300 + buffer[0xF] as usize];
            buffer = words_to_bytes16(&block);
            if let Some(t) = trace.as_mut() {
                t.push((i as u8, 10, buffer));
            }
        }

        // 第二次置换（table_s10），在 j 循环之后做一次。
        buffer[0x0] = TABLE_S10[(0x0 << 8) + buffer[0x0] as usize];
        buffer[0x4] = TABLE_S10[(0x4 << 8) + buffer[0x4] as usize];
        buffer[0x8] = TABLE_S10[(0x8 << 8) + buffer[0x8] as usize];
        buffer[0xc] = TABLE_S10[(0xc << 8) + buffer[0xc] as usize];

        let tmp = buffer[0x0d];
        buffer[0xd] = TABLE_S10[(0xd << 8) + buffer[0x9] as usize];
        buffer[0x9] = TABLE_S10[(0x9 << 8) + buffer[0x5] as usize];
        buffer[0x5] = TABLE_S10[(0x5 << 8) + buffer[0x1] as usize];
        buffer[0x1] = TABLE_S10[(0x1 << 8) + tmp as usize];

        let tmp = buffer[0x02];
        buffer[0x2] = TABLE_S10[(0x2 << 8) + buffer[0xa] as usize];
        buffer[0xa] = TABLE_S10[(0xa << 8) + tmp as usize];
        let tmp = buffer[0x06];
        buffer[0x6] = TABLE_S10[(0x6 << 8) + buffer[0xe] as usize];
        buffer[0xe] = TABLE_S10[(0xe << 8) + tmp as usize];

        let tmp = buffer[0x3];
        buffer[0x3] = TABLE_S10[(0x3 << 8) + buffer[0x7] as usize];
        buffer[0x7] = TABLE_S10[(0x7 << 8) + buffer[0xb] as usize];
        buffer[0xb] = TABLE_S10[(0xb << 8) + buffer[0xf] as usize];
        buffer[0xf] = TABLE_S10[(0xf << 8) + tmp as usize];
        if let Some(t) = trace.as_mut() {
            t.push((i as u8, 11, buffer));
        }

        // 与上一段密文（或 IV）异或。
        let (xor_result, offset) = if mode == 3 {
            let xor_result = if i < 7 {
                xor16(&buffer, &message_in[0x70 - 0x10 * i..0x70 - 0x10 * i + 16])
            } else {
                xor16(&buffer, &MESSAGE_IV[3])
            };
            (xor_result, 0x70 - 0x10 * i)
        } else {
            let xor_result = if i > 0 {
                xor16(&buffer, &message_in[0x10 * i..0x10 * i + 16])
            } else {
                xor16(&buffer, &MESSAGE_IV[mode])
            };
            (xor_result, 0x10 * i)
        };
        if let Some(t) = trace.as_mut() {
            t.push((i as u8, 12, xor_result));
        }
        decrypted[offset..offset + 16].copy_from_slice(&xor_result);
    }

    decrypted
}

/// 参考实现 `Fairplay3.generate_key_schedule`：11 轮 x 4 个 u32 的轮密钥。
pub fn generate_key_schedule(key_material: &[u8]) -> [[u32; 4]; 11] {
    assert!(
        key_material.len() >= 16,
        "generate_key_schedule 需要 16 字节密钥"
    );
    let mut key_schedule = [[0xdeadbeefu32; 4]; 11];
    let mut buffer = xor16(&key_material[..16], &T_KEY);
    let mut ti = 0usize;

    for round in 0..11usize {
        key_schedule[round][0] = le_words16(&buffer)[0];

        let t1 = key_table(ti, buffer[0x0d]);
        let t2 = key_table(ti + 1, buffer[0x0e]);
        let t3 = key_table(ti + 2, buffer[0x0f]);
        let t4 = key_table(ti + 3, buffer[0x0c]);
        ti += 4;

        buffer[0] ^= t1 ^ INDEX_MANGLE[round];
        buffer[1] ^= t2;
        buffer[2] ^= t3;
        buffer[3] ^= t4;

        let mut words = le_words16(&buffer);
        key_schedule[round][1] = words[1];
        words[1] ^= words[0];
        key_schedule[round][2] = words[2];
        words[2] ^= words[1];
        key_schedule[round][3] = words[3];
        words[3] ^= words[2];
        buffer = words_to_bytes16(&words);
    }

    key_schedule
}

fn permute_block1(block: &[u8; 16]) -> [u8; 16] {
    let mut block = *block;

    block[0] = TABLE_S3[block[0] as usize];
    block[4] = TABLE_S3[0x400 + block[4] as usize];
    block[8] = TABLE_S3[0x800 + block[8] as usize];
    block[12] = TABLE_S3[0xc00 + block[12] as usize];

    let tmp = block[13];
    block[13] = TABLE_S3[0x100 + block[9] as usize];
    block[9] = TABLE_S3[0xd00 + block[5] as usize];
    block[5] = TABLE_S3[0x900 + block[1] as usize];
    block[1] = TABLE_S3[0x500 + tmp as usize];

    let tmp = block[2];
    block[2] = TABLE_S3[0xa00 + block[10] as usize];
    block[10] = TABLE_S3[0x200 + tmp as usize];
    let tmp = block[6];
    block[6] = TABLE_S3[0xe00 + block[14] as usize];
    block[14] = TABLE_S3[0x600 + tmp as usize];

    let tmp = block[3];
    block[3] = TABLE_S3[0xf00 + block[7] as usize];
    block[7] = TABLE_S3[0x300 + block[11] as usize];
    block[11] = TABLE_S3[0x700 + block[15] as usize];
    block[15] = TABLE_S3[0xb00 + tmp as usize];

    block
}

fn permute_block2(block: &[u8; 16], round: usize) -> [u8; 16] {
    let mut block = *block;

    block[0] = permute_table2(round * 16 + 0, block[0]);
    block[4] = permute_table2(round * 16 + 4, block[4]);
    block[8] = permute_table2(round * 16 + 8, block[8]);
    block[12] = permute_table2(round * 16 + 12, block[12]);

    let tmp = block[13];
    block[13] = permute_table2(round * 16 + 13, block[9]);
    block[9] = permute_table2(round * 16 + 9, block[5]);
    block[5] = permute_table2(round * 16 + 5, block[1]);
    block[1] = permute_table2(round * 16 + 1, tmp);

    let tmp = block[2];
    block[2] = permute_table2(round * 16 + 2, block[10]);
    block[10] = permute_table2(round * 16 + 10, tmp);
    let tmp = block[6];
    block[6] = permute_table2(round * 16 + 6, block[14]);
    block[14] = permute_table2(round * 16 + 14, tmp);

    let tmp = block[3];
    block[3] = permute_table2(round * 16 + 3, block[7]);
    block[7] = permute_table2(round * 16 + 7, block[11]);
    block[11] = permute_table2(round * 16 + 11, block[15]);
    block[15] = permute_table2(round * 16 + 15, tmp);

    block
}

/// 参考实现 `Fairplay3.cycle`：类 AES 的 9 轮查表变换。
pub fn cycle(block: &[u8; 16], key_schedule: &[[u32; 4]; 11]) -> [u8; 16] {
    let mut words = le_words16(block);
    for i in 0..4 {
        words[i] ^= key_schedule[10][i];
    }
    let mut block = permute_block1(&words_to_bytes16(&words));

    for round in 0..9usize {
        let key = words_to_bytes16(&key_schedule[9 - round]);
        let mut words = le_words16(&block);

        words[0] = TABLE_S5[(block[3] ^ key[3]) as usize]
            ^ TABLE_S6[(block[2] ^ key[2]) as usize]
            ^ TABLE_S8[(block[0] ^ key[0]) as usize]
            ^ TABLE_S7[(block[1] ^ key[1]) as usize];
        block = words_to_bytes16(&words);

        words = le_words16(&block);
        words[1] = TABLE_S5[(block[7] ^ key[7]) as usize]
            ^ TABLE_S6[(block[6] ^ key[6]) as usize]
            ^ TABLE_S7[(block[5] ^ key[5]) as usize]
            ^ TABLE_S8[(block[4] ^ key[4]) as usize];
        block = words_to_bytes16(&words);

        words = le_words16(&block);
        words[2] = TABLE_S5[(block[11] ^ key[11]) as usize]
            ^ TABLE_S6[(block[10] ^ key[10]) as usize]
            ^ TABLE_S7[(block[9] ^ key[9]) as usize]
            ^ TABLE_S8[(block[8] ^ key[8]) as usize];
        block = words_to_bytes16(&words);

        words = le_words16(&block);
        words[3] = TABLE_S5[(block[15] ^ key[15]) as usize]
            ^ TABLE_S6[(block[14] ^ key[14]) as usize]
            ^ TABLE_S7[(block[13] ^ key[13]) as usize]
            ^ TABLE_S8[(block[12] ^ key[12]) as usize];
        block = permute_block2(&words_to_bytes16(&words), 8 - round);
    }

    let mut words = le_words16(&block);
    for i in 0..4 {
        words[i] ^= key_schedule[0][i];
    }
    words_to_bytes16(&words)
}

/// 参考实现 `Fairplay3.generate_session_key`：5 轮 SAPHash + modifiedMD5 混合。
pub fn generate_session_key(old_sap: &[u8], message_in: &[u8]) -> [u8; 16] {
    assert!(
        old_sap.len() >= 0x100,
        "generate_session_key 需要至少 256 字节 SAP"
    );
    let decrypted = decrypt_message(message_in);

    let mut new_sap = Vec::with_capacity(320);
    new_sap.extend_from_slice(&STATIC_SOURCE_1);
    new_sap.extend_from_slice(&decrypted);
    new_sap.extend_from_slice(&old_sap[0x80..0x80 + 0x80]);
    new_sap.extend_from_slice(&STATIC_SOURCE_2);

    let mut session_key = INITIAL_SESSION_KEY;

    for round in 0..5usize {
        let base = &new_sap[round * 64..round * 64 + 64];
        let md5 = modified_md5(base, &session_key);
        let hash = saphash(base);

        let md5_words = le_words16(&md5);
        let hash_words = le_words16(&hash);
        let mut sum = [0u32; 4];
        for i in 0..4 {
            sum[i] = hash_words[i].wrapping_add(md5_words[i]);
        }
        session_key = words_to_bytes16(&sum);
    }

    // 每个 dword 内部反转字节序。
    for i in (0..16).step_by(4) {
        session_key.swap(i, i + 3);
        session_key.swap(i + 1, i + 2);
    }
    for byte in &mut session_key {
        *byte ^= 121;
    }

    session_key
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fairplay3::test_vectors::SESSION_CASES;
    use crate::fairplay3::{hex, unhex, unhex16};

    #[test]
    fn decrypt_message_matches_python() {
        assert!(!SESSION_CASES.is_empty());
        for case in SESSION_CASES {
            let message = unhex(case.message);
            assert_eq!(
                hex(&decrypt_message(&message)),
                case.decrypted,
                "decryptMessage 不一致: {}",
                case.name
            );
        }
    }

    #[test]
    fn session_key_rounds_match_python() {
        assert!(!SESSION_CASES.is_empty());
        for case in SESSION_CASES {
            let message = unhex(case.message);
            let decrypted = decrypt_message(&message);

            let mut new_sap = Vec::with_capacity(320);
            new_sap.extend_from_slice(&STATIC_SOURCE_1);
            new_sap.extend_from_slice(&decrypted);
            new_sap.extend_from_slice(&crate::fairplay3::tables::DEFAULT_SAP[0x80..0x100]);
            new_sap.extend_from_slice(&STATIC_SOURCE_2);

            let mut session_key = INITIAL_SESSION_KEY;
            for (round, expected) in case.rounds.iter().enumerate() {
                let base = &new_sap[round * 64..round * 64 + 64];
                assert_eq!(
                    hex(base),
                    expected.base,
                    "第 {round} 轮 base 不一致: {}",
                    case.name
                );

                let md5 = modified_md5(base, &session_key);
                assert_eq!(
                    hex(&md5),
                    expected.md5,
                    "第 {round} 轮 md5 不一致: {}",
                    case.name
                );

                let hash = saphash(base);
                assert_eq!(
                    hex(&hash),
                    expected.hash,
                    "第 {round} 轮 SAPHash 不一致: {}",
                    case.name
                );

                let md5_words = le_words16(&md5);
                let hash_words = le_words16(&hash);
                let mut sum = [0u32; 4];
                for i in 0..4 {
                    sum[i] = hash_words[i].wrapping_add(md5_words[i]);
                }
                session_key = words_to_bytes16(&sum);
                assert_eq!(
                    hex(&session_key),
                    expected.session,
                    "第 {round} 轮 session key 不一致: {}",
                    case.name
                );
            }

            assert_eq!(
                hex(&generate_session_key(
                    &crate::fairplay3::tables::DEFAULT_SAP,
                    &message
                )),
                case.final_key,
                "generate_session_key 最终值不一致: {}",
                case.name
            );
        }
    }

    #[test]
    fn accepts_full_sap_slice() {
        let message = unhex(SESSION_CASES[0].message);
        let key = generate_session_key(&crate::fairplay3::tables::DEFAULT_SAP, &message);
        let expected = unhex16(SESSION_CASES[0].final_key);
        assert_eq!(key, expected);
    }
}

#[cfg(test)]
mod trace_tests {
    use super::*;
    use crate::fairplay3::hex;

    /// `decrypt_message_traced` 仅用于调试/对照；这里确认它的阶段 12 就是最终明文块，
    /// 防止以后重构时把中间值记录点放错位置。
    #[test]
    fn traced_blocks_line_up_with_output() {
        let case = &crate::fairplay3::test_vectors::SESSION_CASES[0];
        let message = crate::fairplay3::unhex(case.message);
        let (out, trace) = decrypt_message_traced(&message);
        // 每块：读入 1 + 9 轮 *（轮变换 + s9）+ s10 1 + 异或 1 = 21 条。
        assert_eq!(trace.len(), 8 * (1 + 9 * 2 + 1 + 1));
        for (i, chunk) in out.chunks(16).enumerate() {
            let stage = trace
                .iter()
                .find(|(block, kind, _)| *block == i as u8 && *kind == 12)
                .expect("缺少 stage 12");
            assert_eq!(hex(&stage.2), hex(chunk));
        }
    }
}
