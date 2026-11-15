//! `SAPHash`：把 64 字节输入块 + 固定初值揉成 16 字节 SAP 摘要。
//!
//! 参考实现先做 840 轮三重旋转混合，再交给 [`super::garble`]，最后把 5 个缓冲区
//! 折叠进 16 字节并做一次反向旋转。这里逐句对照 Python 实现。

use super::garble::garble;
use super::tables::{
    SAPHASH_BUFFER0, SAPHASH_BUFFER2, SAPHASH_BUFFER4, SAPHASH_I0_INDEX,
};

#[inline]
fn rol8(x: u8, count: u32) -> u8 {
    ((x << count) | (x >> (8 - count))) & 0xff
}

fn load_input(block_in: &[u8]) -> [u8; 210] {
    let mut buffer1 = [0u8; 210];
    for i in 0..210 {
        let word = u32::from_le_bytes([
            block_in[(i % 64) / 4 * 4],
            block_in[(i % 64) / 4 * 4 + 1],
            block_in[(i % 64) / 4 * 4 + 2],
            block_in[(i % 64) / 4 * 4 + 3],
        ]);
        buffer1[i] = ((word >> ((3 - (i % 4)) * 8)) & 0xff) as u8;
    }
    buffer1
}

fn scramble(buffer1: &mut [u8; 210]) {
    for i in 0..840usize {
        let idx = |off: u32| (i as u32).wrapping_sub(off) as usize % 210;
        let x = buffer1[idx(155)];
        let y = buffer1[idx(57)];
        let z = buffer1[idx(13)];
        let w = buffer1[idx(0)];
        buffer1[i % 210] =
            (rol8(y, 5) as i64 + (rol8(z, 3) ^ w) as i64 - rol8(x, 7) as i64) as u8;
    }
}

/// 参考实现 `SAPHash.hash`。输入至少 64 字节且长度为 4 的倍数。
pub fn saphash(block_in: &[u8]) -> [u8; 16] {
    assert!(block_in.len() >= 64, "SAPHash 需要至少 64 字节输入");
    assert_eq!(block_in.len() % 4, 0, "SAPHash 输入长度必须是 4 的倍数");

    let mut buffer1 = load_input(block_in);
    scramble(&mut buffer1);

    let (buffer0, buffer1, buffer2, buffer3, _buffer4) = garble(
        SAPHASH_BUFFER0,
        buffer1,
        SAPHASH_BUFFER2,
        [0u8; 132],
        SAPHASH_BUFFER4,
    );

    let mut key_out = [0xE1u8; 16];
    for i in 0..11 {
        if i == 3 {
            key_out[i] = 0x3d;
        } else {
            key_out[i] = key_out[i].wrapping_add(buffer3[SAPHASH_I0_INDEX[i] * 4]);
        }
    }

    for i in 0..20 {
        key_out[i % 16] ^= buffer0[i];
    }
    for i in 0..35 {
        key_out[i % 16] ^= buffer2[i];
    }
    for i in 0..210 {
        key_out[i % 16] ^= buffer1[i];
    }

    // 反向旋转：原地更新 256 次，顺序与 Python 的双层循环一致。
    for _ in 0..16 {
        for i in 0..16usize {
            let x = key_out[((i as u32).wrapping_sub(7) as usize) % 16];
            let y = key_out[i];
            let z = key_out[((i as u32).wrapping_sub(37) as usize) % 16];
            let w = key_out[((i as u32).wrapping_sub(177) as usize) % 16];
            key_out[i] = rol8(x, 1) ^ y ^ rol8(z, 6) ^ rol8(w, 5);
        }
    }

    key_out
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fairplay3::test_vectors::SAPHASH_CASES;
    use crate::fairplay3::{hex, unhex};

    #[test]
    fn matches_python_reference() {
        assert!(!SAPHASH_CASES.is_empty());
        for case in SAPHASH_CASES {
            let block = unhex(case.block);
            let got = saphash(&block);
            assert_eq!(hex(&got), case.out, "SAPHash 不一致: {}", case.name);
        }
    }
}

