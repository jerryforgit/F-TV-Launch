//! `HandGarble.Garble`：参考实现里最“黑盒”的一段。
//!
//! 输入是 5 个缓冲区（20/210/35/132/21 字节），内部做一百多步互相依赖的位运算，
//! 其中大量使用 Python 的任意精度整数与 `~`（负数）语义。这里的翻译原则：
//!
//! - 缓冲区元素在每次赋值后都落在 `0..=255`，用 `i64` 保存，避免 `u8` 溢出；
//! - `~x` 直接对应 `!x`（i64 二进制补码，与 Python 对负数的位运算一致）；
//! - `int(a / b)` 对应 i64 截断除法（Python `int()` 同样向零截断）；
//! - 只有最后写回缓冲区时才 `& 0xff`，与 Python 的 bytearray 赋值一致。
//!
//! `set!` 会在测试模式下记录每次写入的 `(buffer, index, value)`，用于和 Python
//! 版逐句输出的中间值对照。

#[inline]
fn rol8(x: i64, count: u32) -> i64 {
    (((x << count) & 0xff) | ((x & 0xff) >> (8 - count))) & 0xff
}

/// 注意：参考实现的 `rol8x` 不像 `rol8` 那样掩码，结果可能超过 8 位。
#[inline]
fn rol8x(x: i64, count: u32) -> i64 {
    (x << count) | (x >> (8 - count))
}

#[inline]
fn weird_ror8(x: i64, count: u32) -> i64 {
    if count == 0 {
        0
    } else {
        ((x >> count) & 0xff) | ((x & 0xff) << (8 - count))
    }
}

#[inline]
fn weird_rol8(x: i64, count: u32) -> i64 {
    if count == 0 {
        0
    } else {
        ((x << count) & 0xff) | ((x & 0xff) >> (8 - count))
    }
}

#[inline]
fn weird_rol32(x: i64, count: u32) -> i64 {
    if count == 0 {
        0
    } else {
        (x << count) ^ (x >> (8 - count))
    }
}

/// 生产路径：不记录中间值。
pub fn garble(
    b0: [u8; 20],
    b1: [u8; 210],
    b2: [u8; 35],
    b3: [u8; 132],
    b4: [u8; 21],
) -> ([u8; 20], [u8; 210], [u8; 35], [u8; 132], [u8; 21]) {
    garble_impl(b0, b1, b2, b3, b4, None)
}

#[cfg(test)]
pub(crate) fn garble_traced(
    b0: [u8; 20],
    b1: [u8; 210],
    b2: [u8; 35],
    b3: [u8; 132],
    b4: [u8; 21],
) -> (
    ([u8; 20], [u8; 210], [u8; 35], [u8; 132], [u8; 21]),
    Vec<(u8, usize, u8)>,
) {
    let mut trace = Vec::new();
    let out = garble_impl(b0, b1, b2, b3, b4, Some(&mut trace));
    (out, trace)
}

fn garble_impl(
    b0: [u8; 20],
    b1: [u8; 210],
    b2: [u8; 35],
    b3: [u8; 132],
    b4: [u8; 21],
    mut trace: Option<&mut Vec<(u8, usize, u8)>>,
) -> ([u8; 20], [u8; 210], [u8; 35], [u8; 132], [u8; 21]) {
    let mut buf0 = [0i64; 20];
    let mut buf1 = [0i64; 210];
    let mut buf2 = [0i64; 35];
    let mut buf3 = [0i64; 132];
    let mut buf4 = [0i64; 21];
    for i in 0..20 {
        buf0[i] = b0[i] as i64;
    }
    for i in 0..210 {
        buf1[i] = b1[i] as i64;
    }
    for i in 0..35 {
        buf2[i] = b2[i] as i64;
    }
    for i in 0..132 {
        buf3[i] = b3[i] as i64;
    }
    for i in 0..21 {
        buf4[i] = b4[i] as i64;
    }

    macro_rules! set {
        ($trace:ident, $id:expr, $buf:ident, $idx:expr, $val:expr) => {{
            let idx = ($idx) as usize;
            let val = ($val) & 0xff;
            $buf[idx] = val;
            if let Some(t) = $trace.as_mut() {
                t.push(($id, idx, val as u8));
            }
        }};
    }
    macro_rules! at {
        ($buf:ident, $idx:expr) => {
            $buf[($idx) as usize]
        };
    }

    // 0
    set!(trace, 2, buf2, 12, 0x14
        + (((buf1[64] & 92) | ((buf1[99] / 3) & 35))
            & at!(buf4, rol8x(at!(buf4, buf1[206] % 21), 4) % 21)));
    set!(trace, 1, buf1, 4, ((buf1[99] / 5) * (buf1[99] / 5) * 2));
    set!(trace, 2, buf2, 34, 0xb8);
    set!(trace, 1, buf1, 153,
        (buf1[153] ^ ((at!(buf2, buf1[203] % 35) * at!(buf2, buf1[203] % 35) * buf1[190]) & 0xff)));
    set!(trace, 0, buf0, 3, buf0[3] - (((at!(buf4, buf1[205] % 21) >> 1) & 80) | 0xe6440));
    set!(trace, 0, buf0, 16, 0x93);
    set!(trace, 0, buf0, 13, 0x62);
    set!(trace, 1, buf1, 33, buf1[33] - (at!(buf4, buf1[36] % 21) & 0xf6));

    let tmp2 = at!(buf2, buf1[67] % 35);
    set!(trace, 2, buf2, 12, 0x07);
    let tmp = at!(buf0, buf1[181] % 20);
    set!(trace, 1, buf1, 2, buf1[2] - 3136);
    set!(trace, 0, buf0, 19, at!(buf4, buf1[58] % 21));
    set!(trace, 3, buf3, 0, 92 - at!(buf2, buf1[32] % 35));
    set!(trace, 3, buf3, 4, at!(buf2, buf1[15] % 35) + 0x9e);
    set!(trace, 1, buf1, 34,
        buf1[34] + (at!(buf4, ((at!(buf2, buf1[15] % 35) + 0x9e) & 0xff) % 21) / 5));
    set!(trace, 0, buf0, 19,
        buf0[19] + 0xfffffee6 - ((at!(buf0, buf3[4] % 20) >> 1) & 102));
    set!(trace, 1, buf1, 15,
        (3 * ((((buf1[72] >> ((at!(buf4, buf1[190] % 21) & 7) as u32))
            ^ (buf1[72] << ((7 - (at!(buf4, buf1[190] % 21) - 1)) & 7) as u32)))
            - (3 * at!(buf4, buf1[126] % 21))))
            ^ buf1[15]);
    set!(trace, 0, buf0, 15,
        buf0[15] ^ (at!(buf2, buf1[181] % 35) * at!(buf2, buf1[181] % 35) * at!(buf2, buf1[181] % 35)));
    set!(trace, 2, buf2, 4, buf2[4] ^ (buf1[202] / 3));

    let a = (92 - at!(buf0, buf3[0] % 20)) & 0xff;
    let e = ((a & 0xc6) | (!buf1[105] & 0xc6) | (a & !buf1[105])) & 0xff;
    set!(trace, 2, buf2, 1, buf2[1] + (e * e * e));
    set!(trace, 0, buf0, 19,
        buf0[19] ^ (((224 | (at!(buf4, buf1[92] % 21) & 27)) * at!(buf2, buf1[41] % 35)) / 3));
    set!(trace, 1, buf1, 140, buf1[140] + weird_ror8(92, (buf1[5] & 7) as u32));
    set!(trace, 2, buf2, 12,
        buf2[12] + ((((!buf1[4] ^ at!(buf2, buf1[12] % 35)) | buf1[182]) & 192)
            | ((!buf1[4] ^ at!(buf2, buf1[12] % 35)) & buf1[182])));
    set!(trace, 1, buf1, 36, buf1[36] + 125);
    set!(trace, 1, buf1, 124,
        rol8x(
            (((74 & buf1[138]) | ((74 | buf1[138]) & buf0[15])) & at!(buf0, buf1[43] % 20))
                | (((74 & buf1[138]) | ((74 | buf1[138]) & buf0[15])
                    | at!(buf0, buf1[43] % 20)) & 95),
            4,
        ));
    set!(trace, 3, buf3, 8,
        ((((at!(buf0, buf3[4] % 20) & 95) & ((at!(buf4, buf1[68] % 21) & 46) << 1)) | 16) ^ 92));

    let a = buf1[177] + at!(buf4, buf1[79] % 21);
    let d = (((a >> 1) | ((3 * buf1[148]) / 5)) & buf2[1])
        | ((a >> 1) & ((3 * buf1[148]) / 5));
    set!(trace, 3, buf3, 12, -34 - d);

    let a = (8 - (buf2[22] & 7)) & 0xff;
    let b = (buf1[33] >> (a & 7) as u32) & 0xff;
    let c = (buf1[33] << (buf2[22] & 7) as u32) & 0xff;
    set!(trace, 2, buf2, 16,
        buf2[16] + (((at!(buf2, buf3[0] % 35) & 159) | at!(buf0, buf3[4] % 20) | 8)
            - ((b ^ c) | 128)));
    set!(trace, 0, buf0, 14, buf0[14] ^ at!(buf2, buf3[12] % 35));

    let a = weird_rol8(
        at!(buf4, at!(buf0, buf1[201] % 20) % 21),
        ((at!(buf2, buf1[112] % 35) << 1) & 7) as u32,
    ) & 0xff;
    let d = ((at!(buf0, buf1[208] % 20) & 131) | (at!(buf0, buf1[164] % 20) & 124)) & 0xff;
    set!(trace, 1, buf1, 19,
        buf1[19] + ((a & (d / 5)) | ((a | (d / 5)) & 37)));
    set!(trace, 2, buf2, 8,
        weird_ror8(
            140,
            (((at!(buf4, buf1[45] % 21) + 92) * (at!(buf4, buf1[45] % 21) + 92)) & 7) as u32,
        ));
    set!(trace, 1, buf1, 190, 56);
    set!(trace, 2, buf2, 8, buf2[8] ^ buf3[0]);
    set!(trace, 1, buf1, 53, !((at!(buf0, buf1[83] % 20) | 204) / 5));
    set!(trace, 0, buf0, 13, buf0[13] + at!(buf0, buf1[41] % 20));
    set!(trace, 0, buf0, 10,
        ((at!(buf2, buf3[0] % 35) & buf1[2])
            | ((at!(buf2, buf3[0] % 35) | buf1[2]) & buf3[12]))
            / 15);

    let a = (((56 | (at!(buf4, buf1[2] % 21) & 68)) | at!(buf2, buf3[8] % 35)) & 42)
        | (((at!(buf4, buf1[2] % 21) & 68) | 56) & at!(buf2, buf3[8] % 35));
    set!(trace, 3, buf3, 16, (a * a) + 110);
    set!(trace, 3, buf3, 20, 202 - buf3[16]);
    set!(trace, 3, buf3, 24, buf1[151]);
    set!(trace, 2, buf2, 13, buf2[13] ^ at!(buf4, buf3[0] % 21));

    let b = (((at!(buf2, buf1[179] % 35) - 38) & 177) | (buf3[12] & 177)) & 0xff;
    let c = ((at!(buf2, buf1[179] % 35) - 38) & buf3[12]) & 0xff;
    set!(trace, 3, buf3, 28, 30 + ((b | c) * (b | c)));
    set!(trace, 3, buf3, 32, buf3[28] + 62);

    let a = (((buf3[20] + (buf3[0] & 74)) | !at!(buf4, buf3[0] % 21)) & 121) & 0xff;
    let b = ((buf3[20] + (buf3[0] & 74)) & !at!(buf4, buf3[0] % 21)) & 0xff;
    let tmp3 = (a | b) & 0xff;
    let c = ((((a | b) ^ 0xffffffa6) | buf3[0]) & 4
        | (((a | b) ^ 0xffffffa6) & buf3[0])) & 0xff;
    set!(trace, 1, buf1, 47, (at!(buf2, buf1[89] % 35) + c) ^ buf1[47]);
    set!(trace, 3, buf3, 36,
        ((rol8((tmp & 179) + 68, 2) & buf0[3]) | (tmp2 & !buf0[3])) - 15);
    set!(trace, 1, buf1, 123, buf1[123] ^ 221);

    let a = ((at!(buf4, buf3[0] % 21) / 3) - at!(buf2, buf3[4] % 35)) & 0xff;
    let c = ((((buf3[0] & 163) + 92) & 246) | (buf3[0] & 92)) & 0xff;
    let e = (((c | buf3[24]) & 54) | (c & buf3[24])) & 0xff;
    set!(trace, 3, buf3, 40, a - e);
    set!(trace, 3, buf3, 44, tmp3 ^ 81 ^ (((buf3[0] >> 1) & 101) + 26));
    set!(trace, 3, buf3, 48, at!(buf2, buf3[4] % 35) & 27);
    set!(trace, 3, buf3, 52, 27);
    set!(trace, 3, buf3, 56, 199);
    set!(trace, 3, buf3, 64,
        (buf3[4] + ((((
            (((buf3[40] | buf3[24]) & 177) | (buf3[40] & buf3[24]))
            & ((((at!(buf4, buf3[0] % 20) & 177) | 176)) | (at!(buf4, buf3[0] % 21) & !3)))
            | ((((buf3[40] & buf3[24]) | ((buf3[40] | buf3[24]) & 177)) & 199)
                | (((((at!(buf4, buf3[0] % 21) & 1) & 0xff) + 176) | (at!(buf4, buf3[0] % 21) & !3))
                    & buf3[56]))) & !buf3[52]) | buf3[48])));
    set!(trace, 2, buf2, 33, buf2[33] ^ buf1[26]);
    set!(trace, 1, buf1, 106, buf1[106] ^ (buf3[20] ^ 133));
    set!(trace, 2, buf2, 30,
        ((buf3[64] / 3) - (275 | (buf3[0] & 247))) ^ at!(buf0, buf1[122] % 20));
    set!(trace, 1, buf1, 22, (at!(buf2, buf1[90] % 35) & 95) | 68);

    let a = ((at!(buf4, buf3[36] % 21) & 184)
        | (at!(buf2, buf3[44] % 35) & !184)) & 0xff;
    set!(trace, 2, buf2, 18, buf2[18] + ((a * a * a) >> 1));
    set!(trace, 2, buf2, 5, buf2[5] - at!(buf4, buf1[92] % 21));

    let a = (((((buf1[41] & 0xff) & !24) | (at!(buf2, buf1[183] % 35) & 24))
        & (buf3[16] + 53))
        | (buf3[20] & at!(buf2, buf3[20] % 35))) & 0xff;
    let b = ((buf1[17] & !buf3[44]) | (at!(buf0, buf1[59] % 20) & buf3[44])) & 0xff;
    set!(trace, 2, buf2, 18, buf2[18] ^ (a * b));

    let a = (weird_ror8(buf1[11], (at!(buf2, buf1[28] % 35) & 7) as u32) & 7) & 0xff;
    let b = ((((at!(buf0, buf1[93] % 20) & !buf0[14]) | (buf0[14] & 150)) & !28)
        | (buf1[7] & 28)) & 0xff;
    set!(trace, 2, buf2, 22,
        ((((b | weird_rol8(at!(buf2, buf3[0] % 35), a as u32)) & buf2[33])
            | (b & weird_rol8(at!(buf2, buf3[0] % 35), a as u32))) + 74));

    let a = at!(buf4, (at!(buf0, buf1[39] % 20) ^ 217) % 21) & 0xff;
    set!(trace, 0, buf0, 15,
        buf0[15] - ((((buf3[20] | buf3[0]) & 214) | (buf3[20] & buf3[0])) & a
            | ((((buf3[20] | buf3[0]) & 214) | (buf3[20] & buf3[0]) | a) & buf3[32])));

    let b = (((at!(buf2, buf1[57] % 35) & at!(buf0, buf3[64] % 20))
        | ((at!(buf0, buf3[64] % 20) | at!(buf2, buf1[57] % 35)) & 95)
        | (buf3[64] & 45) | 82) & 32) & 0xff;
    let c = (((at!(buf2, buf1[57] % 35) & at!(buf0, buf3[64] % 20))
        | ((at!(buf2, buf1[57] % 35) | at!(buf0, buf3[64] % 20)) & 95))
        & ((buf3[64] & 45) | 82)) & 0xff;
    let d = (((buf3[0] / 3) - (buf3[64] | buf1[22])) ^ (buf3[28] + 62) ^ (b | c)) & 0xff;
    let t = at!(buf0, d % 20) & 0xff;
    set!(trace, 3, buf3, 68,
        (at!(buf0, buf1[99] % 20) * at!(buf0, buf1[99] % 20) * at!(buf0, buf1[99] % 20)
            * at!(buf0, buf1[99] % 20))
            | at!(buf2, buf3[64] % 35));

    let u = at!(buf0, buf1[50] % 20);
    let w = at!(buf2, buf1[138] % 35);
    let x = at!(buf4, buf1[39] % 21);
    let y = at!(buf0, buf1[4] % 20);
    let z = at!(buf4, buf1[202] % 21);
    let v = at!(buf0, buf1[151] % 20);
    let s = at!(buf2, buf1[14] % 35);
    let r = at!(buf0, buf1[145] % 20);

    let a = ((at!(buf2, buf3[68] % 35) & at!(buf0, buf1[209] % 20))
        | ((at!(buf2, buf3[68] % 35) | at!(buf0, buf1[209] % 20)) & 24)) & 0xff;
    let b = weird_rol8(
        at!(buf4, buf1[127] % 21),
        (at!(buf2, buf3[68] % 35) & 7) as u32,
    ) & 0xff;
    let c = (a & buf0[10]) | (b & !buf0[10]) & 0xff;
    let d = (7 ^ (at!(buf4, at!(buf2, buf3[36] % 35) % 21) << 1)) & 0xff;
    set!(trace, 3, buf3, 72, (c & 71) | (d & !71));
    set!(trace, 2, buf2, 2,
        buf2[2] + ((((at!(buf0, buf3[20] % 20) << 1) & 159)
            | (at!(buf4, buf1[190] % 21) & !159))
            & ((((at!(buf4, buf3[64] % 21) & 110)
                | (at!(buf0, buf1[25] % 20) & !110)) & !150)
                | (buf1[25] & 150))));
    set!(trace, 2, buf2, 14,
        buf2[14] - (((at!(buf2, buf3[20] % 35)
            & (buf3[72] ^ at!(buf2, buf1[100] % 35))) & !34)
            | (buf1[97] & 34)));
    set!(trace, 0, buf0, 17, 115);
    let x23 = ((at!(buf4, buf1[17] % 21) | at!(buf0, buf3[20] % 20)) & buf3[72])
        | (at!(buf4, buf1[17] % 21) & at!(buf0, buf3[20] % 20));
    set!(trace, 1, buf1, 23,
        buf1[23] ^ (((x23 & (buf1[50] / 3))
            | ((x23 | (buf1[50] / 3)) & 246)) << 1));
    set!(trace, 0, buf0, 13,
        (((((at!(buf0, buf3[40] % 20) | buf1[10]) & 82)
            | (at!(buf0, buf3[40] % 20) & buf1[10])) & 209)
            | ((at!(buf0, buf1[39] % 20) << 1) & 46)) >> 1);
    set!(trace, 2, buf2, 33, buf2[33] - (buf1[113] & 9));
    set!(trace, 2, buf2, 28,
        buf2[28] - ((((2 | (buf1[110] & 222)) >> 1) & !223) | (buf3[20] & 223)));

    let j = weird_rol8(v | z, (u & 7) as u32);
    let a = (buf2[16] & t) | (w & !buf2[16]);
    let b = (buf1[33] & 17) | (x & !17);
    let e = ((y | ((a + b) / 5)) & 147) | (y & ((a + b) / 5));
    let m = (buf3[40] & at!(buf4, ((buf3[8] + j + e) & 0xff) % 21))
        | ((buf3[40] | at!(buf4, ((buf3[8] + j + e) & 0xff) % 21)) & buf2[23]);
    set!(trace, 0, buf0, 15,
        (((at!(buf4, buf3[20] % 21) - 48) & !buf1[184])
            | ((at!(buf4, buf3[20] % 21) - 48) & 189)
            | (189 & !buf1[184])) & (m * m * m));
    set!(trace, 2, buf2, 22, buf2[22] + buf1[183]);
    set!(trace, 3, buf3, 76, (3 * at!(buf4, buf1[1] % 21)) ^ buf3[0]);

    let a = at!(buf2, ((buf3[8] + j + e) & 0xff) % 35) & 0xff;
    let f = (((at!(buf4, buf1[178] % 21) & a)
        | ((at!(buf4, buf1[178] % 21) | a) & 209))
        * at!(buf0, buf1[13] % 20))
        * (at!(buf4, buf1[26] % 21) >> 1);
    let g = (f + 0x733ffff9) * 198 - (((f + 0x733ffff9) * 396 + 212) & 212) + 85;
    set!(trace, 3, buf3, 80, buf3[36] + (g ^ 148) + ((g ^ 107) << 1) - 127);
    set!(trace, 3, buf3, 84,
        (at!(buf2, buf3[64] % 35) & 245) | (at!(buf2, buf3[20] % 35) & 10));

    let a = at!(buf0, buf3[68] % 20) | 81;
    set!(trace, 2, buf2, 18,
        buf2[18] - (((a * a * a) & !buf0[15]) | ((buf3[80] / 15) & buf0[15])));
    set!(trace, 3, buf3, 88,
        buf3[8] + j + e - at!(buf0, buf1[160] % 20)
            + (at!(buf4, at!(buf0, ((buf3[8] + j + e) & 255) % 20) % 21) / 3));

    let b = ((r ^ buf3[72]) & !198) | ((s * s) & 198);
    let f = (at!(buf4, buf1[69] % 21) & buf1[172])
        | ((at!(buf4, buf1[69] % 21) | buf1[172]) & ((buf3[12] - b) + 77));
    set!(trace, 0, buf0, 16,
        147 - ((buf3[72] & ((f & 251) | 1)) | (((f & 250) | buf3[72]) & 198)));

    let c = (at!(buf4, buf1[168] % 21) & at!(buf0, buf1[29] % 20) & 7)
        | ((at!(buf4, buf1[168] % 21) | at!(buf0, buf1[29] % 20)) & 6);
    let f = (at!(buf4, buf1[155] % 21) & buf1[105])
        | ((at!(buf4, buf1[155] % 21) | buf1[105]) & 141);
    set!(trace, 0, buf0, 3,
        buf0[3] - at!(buf4, weird_rol32(f, c as u32) % 21));
    set!(trace, 1, buf1, 5,
        weird_ror8(buf0[12], ((at!(buf0, buf1[61] % 20) / 5) & 7) as u32)
            ^ ((!at!(buf2, buf3[84] % 35) & 0xffffffff) / 5));
    set!(trace, 1, buf1, 198, buf1[198] + buf1[3]);

    let a = 162 | at!(buf2, buf3[64] % 35);
    set!(trace, 1, buf1, 164, buf1[164] + (a * a) / 5);
    let g = weird_ror8(139, (buf3[80] & 7) as u32);
    let c = ((at!(buf4, buf3[64] % 21) * at!(buf4, buf3[64] % 21)
        * at!(buf4, buf3[64] % 21)) & 95)
        | (at!(buf0, buf3[40] % 20) & !95);
    set!(trace, 3, buf3, 92,
        (g & 12) | (at!(buf0, buf3[20] % 20) & 12)
            | (g & at!(buf0, buf3[20] % 20)) | c);
    set!(trace, 2, buf2, 12,
        buf2[12] + (((buf1[103] & 32)
            | (buf3[92] & (buf1[103] | 60)) | 16) / 3));
    set!(trace, 3, buf3, 96, buf1[143]);
    set!(trace, 3, buf3, 100, 27);
    set!(trace, 3, buf3, 104,
        (((buf3[40] & !buf2[8]) | (buf1[35] & buf2[8])) & buf3[64]) ^ 119);
    set!(trace, 3, buf3, 108,
        238 & ((((buf3[40] & !buf2[8]) | (buf1[35] & buf2[8])) & buf3[64]) << 1));
    set!(trace, 3, buf3, 112, (!buf3[64] & (buf3[84] / 3)) ^ 49);
    set!(trace, 3, buf3, 116, 98 & ((!buf3[64] & (buf3[84] / 3)) << 1));

    let a = (buf1[35] & buf2[8]) | (buf3[40] & !buf2[8]);
    let b = (a & buf3[64]) | ((buf3[84] / 3) & !buf3[64]);
    set!(trace, 1, buf1, 143,
        buf3[96] - ((b & (86 + ((buf1[172] & 64) >> 1)))
            | (((((buf1[172] & 65) >> 1) ^ 86)
                | ((!buf3[64] & (buf3[84] / 3))
                    | (((buf3[40] & !buf2[8]) | (buf1[35] & buf2[8])) & buf3[64])))
                & buf3[100])));
    // 参考实现这里只赋值、不打印，因此不记录 trace。
    buf2[29] = 162;

    let a = (((at!(buf4, buf3[88] % 21) & 160)
        | (at!(buf0, buf1[125] % 20) & 95)) >> 1) & 0xff;
    let b = at!(buf2, (buf1[149] & 0xff) % 35) ^ ((buf1[43] * buf1[43]) & 0xff);
    set!(trace, 0, buf0, 15, buf0[15] + ((b & a) | ((a | b) & 115)));
    set!(trace, 3, buf3, 120, buf3[64] - at!(buf0, buf3[40] % 20));
    set!(trace, 1, buf1, 95, at!(buf4, buf3[20] % 21));

    let a = weird_ror8(
        at!(buf2, buf3[80] % 35),
        ((at!(buf2, buf1[17] % 35) * at!(buf2, buf1[17] % 35)
            * at!(buf2, buf1[17] % 35)) & 7) as u32,
    );
    set!(trace, 0, buf0, 7, buf0[7] - (a * a));
    set!(trace, 2, buf2, 8,
        buf2[8] - buf1[184] + (at!(buf4, buf1[202] % 21)
            * at!(buf4, buf1[202] % 21) * at!(buf4, buf1[202] % 21)));
    set!(trace, 0, buf0, 16, (at!(buf2, buf1[102] % 35) << 1) & 132);
    set!(trace, 3, buf3, 124, (at!(buf4, buf3[40] % 21) >> 1) ^ buf3[68]);
    set!(trace, 0, buf0, 7,
        buf0[7] - (at!(buf0, buf1[191] % 20)
            - (((at!(buf4, buf1[80] % 21) << 1) & !177)
                | (at!(buf4, at!(buf4, buf3[88] % 21) % 21) & 177))));
    set!(trace, 0, buf0, 6, at!(buf0, buf1[119] % 20));

    let a = (at!(buf4, buf1[190] % 21) & !209) | (buf1[118] & 209);
    let b = at!(buf0, buf3[120] % 20) * at!(buf0, buf3[120] % 20);
    set!(trace, 0, buf0, 12,
        (at!(buf0, buf3[84] % 20) ^ (at!(buf2, buf1[71] % 35) + at!(buf2, buf1[15] % 35)))
            & ((a & b) | ((a | b) & 27)));

    let b = (buf1[32] & at!(buf2, buf3[88] % 35))
        | ((buf1[32] | at!(buf2, buf3[88] % 35)) & 23);
    let d = ((at!(buf4, buf1[57] % 21) * 231) & 169) | (b & 86);
    let f = (((at!(buf0, buf1[82] % 20) & !29) | (at!(buf4, buf3[124] % 21) & 29)) & 190)
        | (at!(buf4, (d / 5) % 21) & !190);
    let h = at!(buf0, buf3[40] % 20) * at!(buf0, buf3[40] % 20) * at!(buf0, buf3[40] % 20);
    let k = (h & buf1[82]) | (h & 92) | (buf1[82] & 92);
    set!(trace, 3, buf3, 128, ((f & k) | ((f | k) & 192)) ^ (d / 5));
    set!(trace, 2, buf2, 25,
        buf2[25] ^ (((at!(buf0, buf3[120] % 20) << 1) * buf1[5])
            - (weird_rol8(buf3[76], (at!(buf4, buf3[124] % 21) & 7) as u32)
                & (buf3[20] + 110))));

    let mut out0 = [0u8; 20];
    let mut out1 = [0u8; 210];
    let mut out2 = [0u8; 35];
    let mut out3 = [0u8; 132];
    let mut out4 = [0u8; 21];
    for i in 0..20 {
        out0[i] = buf0[i] as u8;
    }
    for i in 0..210 {
        out1[i] = buf1[i] as u8;
    }
    for i in 0..35 {
        out2[i] = buf2[i] as u8;
    }
    for i in 0..132 {
        out3[i] = buf3[i] as u8;
    }
    for i in 0..21 {
        out4[i] = buf4[i] as u8;
    }
    (out0, out1, out2, out3, out4)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fairplay3::test_vectors::GARBLE_CASES;
    use crate::fairplay3::unhex;

    fn fixed<const N: usize>(s: &str) -> [u8; N] {
        let v = unhex(s);
        assert_eq!(v.len(), N, "向量长度应为 {N}，实际 {}", v.len());
        v.try_into().unwrap()
    }

    #[test]
    fn matches_python_reference_and_intermediate_writes() {
        assert!(!GARBLE_CASES.is_empty());
        for case in GARBLE_CASES {
            let inputs: [&str; 5] = case.inputs;
            let (out, trace) = garble_traced(
                fixed::<20>(inputs[0]),
                fixed::<210>(inputs[1]),
                fixed::<35>(inputs[2]),
                fixed::<132>(inputs[3]),
                fixed::<21>(inputs[4]),
            );

            let outs = [
                out.0.as_slice(),
                out.1.as_slice(),
                out.2.as_slice(),
                out.3.as_slice(),
                out.4.as_slice(),
            ];
            for (i, got) in outs.iter().enumerate() {
                let expected = unhex(case.outputs[i]);
                assert_eq!(got.len(), expected.len(), "Garble 输出 buffer{i} 长度不一致: {}", case.name);
                if let Some(pos) = got.iter().zip(expected.iter()).position(|(a, b)| a != b) {
                    panic!(
                        "Garble 输出 buffer{i} 第 {pos} 字节不一致: {:02x} != {:02x} ({})",
                        got[pos], expected[pos], case.name
                    );
                }
            }

            let got_trace: Vec<String> = trace
                .iter()
                .map(|(buf, idx, value)| format!("{buf}:{idx}:{value:02x}"))
                .collect();
            let common = got_trace.len().min(case.trace.len());
            for i in 0..common {
                if got_trace[i] != case.trace[i] {
                    panic!(
                        "Garble 第 {i} 条中间值不一致: {}（got {:?} / expected {:?}）",
                        case.name, got_trace[i], case.trace[i]
                    );
                }
            }
            assert_eq!(
                got_trace.len(),
                case.trace.len(),
                "Garble 中间写次数不一致: {}（多出: {:?}）",
                case.name,
                &got_trace[common..]
            );
        }
    }
}
