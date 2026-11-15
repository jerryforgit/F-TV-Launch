//! `modifiedMD5`：参考实现里“改过”的 MD5。
//!
//! 与标准 MD5 的差别：
//! - 输入固定为 64 字节 block + 16 字节 key，不做长度填充；
//! - 第 32 轮（i == 31）后按 A/B/C/D 的低 4 位交换 block 的 dword；
//! - 输出是 `key + (A,B,C,D)` 的 32 位截断。
//!
//! Python 版对 A/B/C/D 只在循环开头掩码 A，其余值可能超过 32 位；但后续只用到
//! 低 32 位（`& 0xffffffff`、`Rol` 以及低 4 位下标），因此这里用 u32 wrapping
//! 运算与原实现逐位等价。
//!
//! 另一个细节：Python 的 `out_words = array('I', memoryview(bytearray(16)))` 会得到
//! 16 个 u32（64 字节），后 48 字节恒为 0，调用方只取前 4 个 word。本函数返回真正
//! 的 16 字节摘要，数值与 Python 返回值的前 16 字节一致。

use super::tables::{MD5_CONSTANTS, MD5_SHIFT};

#[inline]
fn f(b: u32, c: u32, d: u32) -> u32 {
    (b & c) | (!b & d)
}

#[inline]
fn g(b: u32, c: u32, d: u32) -> u32 {
    (b & d) | (c & !d)
}

#[inline]
fn h(b: u32, c: u32, d: u32) -> u32 {
    b ^ c ^ d
}

#[inline]
fn func_j(b: u32, c: u32, d: u32) -> u32 {
    c ^ (b | !d)
}

#[inline]
fn read_word(bytes: &[u8], index: usize) -> u32 {
    u32::from_le_bytes([
        bytes[index * 4],
        bytes[index * 4 + 1],
        bytes[index * 4 + 2],
        bytes[index * 4 + 3],
    ])
}

/// 参考实现 `modifiedMD5.modifiedMD5`。
///
/// `block` 至少 64 字节、`key` 至少 16 字节；只使用前 64 / 16 字节。
pub fn modified_md5(block: &[u8], key: &[u8]) -> [u8; 16] {
    assert!(block.len() >= 64, "modifiedMD5 需要至少 64 字节 block");
    assert!(key.len() >= 16, "modifiedMD5 需要至少 16 字节 key");

    let mut block_words = [0u32; 16];
    for (i, word) in block_words.iter_mut().enumerate() {
        *word = read_word(block, i);
    }

    let key_words = [
        read_word(key, 0),
        read_word(key, 1),
        read_word(key, 2),
        read_word(key, 3),
    ];

    let (mut a, mut b, mut c, mut d) = (key_words[0], key_words[1], key_words[2], key_words[3]);

    for i in 0..64usize {
        let word = if i < 16 {
            i
        } else if i < 32 {
            (5 * i + 1) % 16
        } else if i < 48 {
            (3 * i + 5) % 16
        } else {
            7 * i % 16
        };

        // 参考实现从 blockIn 字节流按大端读取 _input；block_words 是小端视图。
        let input = block_words[word].swap_bytes();
        let mut z = a.wrapping_add(input).wrapping_add(MD5_CONSTANTS[i]);
        z = match i {
            0..=15 => z.wrapping_add(f(b, c, d)).rotate_left(MD5_SHIFT[i] as u32),
            16..=31 => z.wrapping_add(g(b, c, d)).rotate_left(MD5_SHIFT[i] as u32),
            32..=47 => z.wrapping_add(h(b, c, d)).rotate_left(MD5_SHIFT[i] as u32),
            _ => z.wrapping_add(func_j(b, c, d)).rotate_left(MD5_SHIFT[i] as u32),
        };
        z = z.wrapping_add(b);

        let tmp = d;
        d = c;
        c = b;
        b = z;
        a = tmp;

        if i == 31 {
            // Python: 按 A/B/C/D 的低 4 位、第 4/8/12 个 nibble 交换 block dword。
            swap(&mut block_words, (a & 15) as usize, (b & 15) as usize);
            swap(&mut block_words, (c & 15) as usize, (d & 15) as usize);
            for shift in [4usize, 8, 12] {
                let ai = ((a >> shift) & 15) as usize;
                let bi = ((b >> shift) & 15) as usize;
                swap(&mut block_words, ai, bi);
            }
        }
    }

    let out = [
        key_words[0].wrapping_add(a),
        key_words[1].wrapping_add(b),
        key_words[2].wrapping_add(c),
        key_words[3].wrapping_add(d),
    ];
    let mut result = [0u8; 16];
    for (i, word) in out.iter().enumerate() {
        result[i * 4..i * 4 + 4].copy_from_slice(&word.to_le_bytes());
    }
    result
}

#[inline]
fn swap(words: &mut [u32; 16], a: usize, b: usize) {
    words.swap(a, b);
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fairplay3::test_vectors::MD5_CASES;
    use crate::fairplay3::{hex, unhex};

    #[test]
    fn matches_python_reference() {
        assert!(!MD5_CASES.is_empty());
        for case in MD5_CASES {
            let block = unhex(case.block);
            let key = unhex(case.key);
            let got = modified_md5(&block, &key);
            assert_eq!(hex(&got), case.out, "modifiedMD5 不一致: {}", case.name);
        }
    }

    #[test]
    fn only_first_64_and_16_bytes_are_used() {
        let block = vec![0x5a; 96];
        let key = vec![0x3c; 32];
        let expected = modified_md5(&block[..64], &key[..16]);
        assert_eq!(modified_md5(&block, &key), expected);
    }
}
