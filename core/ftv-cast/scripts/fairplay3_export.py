#!/usr/bin/env python3
"""从参考实现 ap2/fairplay3.py 导出 Rust 常量表与测试向量。

用法：
    python3 scripts/fairplay3_export.py [--source /tmp/fairplay3.py]

生成：
    src/fairplay3/tables.rs        常量表（字节表 / u32 表 / MD5 常量）
    src/fairplay3/test_vectors.rs  Python 版跑出的参考向量（输入、中间值、输出）

注意：Python 端 array('I') 依赖本机小端序；脚本在导出时检查 sys.byteorder。
"""

from __future__ import annotations

import argparse
import importlib.util
import io
import re
import sys
import textwrap
from contextlib import redirect_stdout
from pathlib import Path

HERE = Path(__file__).resolve().parent
SRC_DIR = HERE.parent / "src" / "fairplay3"

U8_TABLES = [
    ("TABLE_S1", "table_s1"),
    ("TABLE_S2", "table_s2"),
    ("TABLE_S3", "table_s3"),
    ("TABLE_S4", "table_s4"),
    ("TABLE_S10", "table_s10"),
    ("DEFAULT_SAP", "default_sap"),
    ("SAP_IV", "sap_iv"),
    ("Z_KEY", "z_key"),
    ("X_KEY", "x_key"),
    ("T_KEY", "t_key"),
    ("INDEX_MANGLE", "indexMangle"),
    ("STATIC_SOURCE_1", "static_source_1"),
    ("STATIC_SOURCE_2", "static_source_2"),
    ("INITIAL_SESSION_KEY", "initial_session_key"),
    ("SAPHASH_BUFFER0", None),
    ("SAPHASH_BUFFER2", None),
    ("SAPHASH_BUFFER4", None),
]

U32_TABLES = [
    ("TABLE_S5", "table_s5"),
    ("TABLE_S6", "table_s6"),
    ("TABLE_S7", "table_s7"),
    ("TABLE_S8", "table_s8"),
    ("TABLE_S9", "table_s9"),
]

# SAPHash.hash 里的固定初值（Python 中为函数局部变量）。
SAPHASH_BUFFER0 = [0x96, 0x5F, 0xC6, 0x53, 0xF8, 0x46, 0xCC, 0x18, 0xDF, 0xBE, 0xB2, 0xF8, 0x38, 0xD7, 0xEC, 0x22, 0x03, 0xD1, 0x20, 0x8F]
SAPHASH_BUFFER2 = [0x43, 0x54, 0x62, 0x7A, 0x18, 0xC3, 0xD6, 0xB3, 0x9A, 0x56, 0xF6, 0x1C, 0x14, 0x3F, 0x0C, 0x1D, 0x3B, 0x36, 0x83, 0xB1, 0x39, 0x51, 0x4A, 0xAA, 0x09, 0x3E, 0xFE, 0x44, 0xAF, 0xDE, 0xC3, 0x20, 0x9D, 0x42, 0x3A]
SAPHASH_BUFFER4 = [0xED, 0x25, 0xD1, 0xBB, 0xBC, 0x27, 0x9F, 0x02, 0xA2, 0xA9, 0x11, 0x00, 0x0C, 0xB3, 0x52, 0xC0, 0xBD, 0xE3, 0x1B, 0x49, 0xC7]


def load_module(path: Path):
    spec = importlib.util.spec_from_file_location("fairplay3_ref", path)
    if spec is None or spec.loader is None:
        raise SystemExit(f"无法加载 {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def as_u8_list(values, name: str) -> list[int]:
    out = [int(v) for v in values]
    bad = [v for v in out if not 0 <= v <= 0xFF]
    if bad:
        raise SystemExit(f"{name} 含越界字节: {bad[:8]}")
    return out


def fmt_u8(name: str, values, per_line: int = 24) -> str:
    out = [f"pub const {name}: [u8; {len(values)}] = ["]
    for i in range(0, len(values), per_line):
        chunk = ", ".join(f"0x{v:02x}" for v in values[i : i + per_line])
        out.append(f"    {chunk},")
    out.append("];")
    return "\n".join(out)


def fmt_u32(name: str, values, per_line: int = 6) -> str:
    out = [f"pub const {name}: [u32; {len(values)}] = ["]
    for i in range(0, len(values), per_line):
        chunk = ", ".join(f"0x{v:08x}" for v in values[i : i + per_line])
        out.append(f"    {chunk},")
    out.append("];")
    return "\n".join(out)


def fmt_nested_u8(name: str, rows, per_line: int = 16) -> str:
    rows = [as_u8_list(r, name) for r in rows]
    widths = {len(r) for r in rows}
    if len(widths) != 1:
        raise SystemExit(f"{name} 各行长度不一致: {widths}")
    width = widths.pop()
    out = [f"pub const {name}: [[u8; {width}]; {len(rows)}] = ["]
    for row in rows:
        out.append("    [")
        for i in range(0, len(row), per_line):
            chunk = ", ".join(f"0x{v:02x}" for v in row[i : i + per_line])
            out.append(f"        {chunk},")
        out.append("    ],")
    out.append("];")
    return "\n".join(out)


def build_tables(m) -> str:
    parts = [
        "//! 由 `scripts/fairplay3_export.py` 从参考实现 ap2/fairplay3.py 自动生成，请勿手改。\n"
        "//!\n"
        "//! 参考 Python 通过 `array.array('I')` 按本机小端序解释 dword；Rust 侧统一\n"
        "//! 使用 `u32::from_le_bytes` / `to_le_bytes` 保持字节序一致。\n"
    ]

    for rust_name, py_name in U8_TABLES:
        if py_name is None:
            continue
        values = as_u8_list(getattr(m, py_name), py_name)
        parts.append(fmt_u8(rust_name, values))

    for rust_name, py_name in U32_TABLES:
        values = [int(v) for v in getattr(m, py_name)]
        bad = [v for v in values if not 0 <= v <= 0xFFFFFFFF]
        if bad:
            raise SystemExit(f"{py_name} 含越界 u32: {bad[:4]}")
        parts.append(fmt_u32(rust_name, values))

    parts.append(fmt_nested_u8("MESSAGE_KEY", m.message_key))
    parts.append(fmt_nested_u8("MESSAGE_IV", m.message_iv))

    parts.append(fmt_u8("SAPHASH_BUFFER0", SAPHASH_BUFFER0))
    parts.append(fmt_u8("SAPHASH_BUFFER2", SAPHASH_BUFFER2))
    parts.append(fmt_u8("SAPHASH_BUFFER4", SAPHASH_BUFFER4))
    parts.append("pub const SAPHASH_I0_INDEX: [usize; 11] = [18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30];")

    # modifiedMD5 的 64 个常量与移位表。用与 Python 完全相同的 double 表达式预先算好，
    # 避免 Rust 侧依赖浮点 sin 实现。
    md5_consts = [int((1 << 32) * abs(__import__("math").sin(i + 1))) for i in range(64)]
    shift = [7, 12, 17, 22] * 4 + [5, 9, 14, 20] * 4 + [4, 11, 16, 23] * 4 + [6, 10, 15, 21] * 4
    parts.append(fmt_u32("MD5_CONSTANTS", md5_consts))
    parts.append(fmt_u8("MD5_SHIFT", shift))

    return "\n\n".join(parts) + "\n"


TRACE_RE = re.compile(r"^buffer(\d)\[(\d+)\]:\s*0x([0-9a-fA-F]+)$")


def hexs(data) -> str:
    if isinstance(data, (bytes, bytearray, list, tuple)):
        return bytes(bytearray(int(x) for x in data)).hex()
    return bytes(data).hex()


def run_quiet(fn, *args):
    buf = io.StringIO()
    with redirect_stdout(buf):
        result = fn(*args)
    return result, buf.getvalue()


def build_vectors(m) -> str:
    cases = []

    # ---- HandGarble ----
    def garble_case(name, b0, b1, b2, b3, b4):
        out, stdout = run_quiet(m.HandGarble().Garble, list(b0), list(b1), list(b2), list(b3), list(b4))
        trace = []
        for line in stdout.splitlines():
            mt = TRACE_RE.match(line)
            if mt:
                trace.append(f"{mt.group(1)}:{mt.group(2)}:{int(mt.group(3), 16):02x}")
        if len(trace) < 100:
            raise SystemExit(f"Garble({name}) 只捕获到 {len(trace)} 条中间值，参考实现可能变了")
        return {
            "name": name,
            "b0": hexs(b0),
            "b1": hexs(b1),
            "b2": hexs(b2),
            "b3": hexs(b3),
            "b4": hexs(b4),
            "out": [hexs(x) for x in out],
            "trace": trace,
        }

    cases.append(garble_case(
        "pattern",
        bytes(range(20)),
        bytes((i * 7 + 3) & 0xFF for i in range(210)),
        bytes((i * 11 + 5) & 0xFF for i in range(35)),
        bytes((i * 13 + 1) & 0xFF for i in range(132)),
        bytes((i * 17 + 9) & 0xFF for i in range(21)),
    ))
    cases.append(garble_case(
        "saphash-consts",
        SAPHASH_BUFFER0,
        bytes((i * 3 + 1) & 0xFF for i in range(210)),
        SAPHASH_BUFFER2,
        bytes(132),
        SAPHASH_BUFFER4,
    ))
    cases.append(garble_case(
        "saphash-zeros",
        SAPHASH_BUFFER0,
        bytes(210),
        SAPHASH_BUFFER2,
        bytes(132),
        SAPHASH_BUFFER4,
    ))
    cases.append(garble_case(
        "ramp",
        bytes((0xF0 + i) & 0xFF for i in range(20)),
        bytes(range(210)),
        bytes((0xA0 + i) & 0xFF for i in range(35)),
        bytes((i * 29) & 0xFF for i in range(132)),
        bytes(range(21)),
    ))

    # ---- modifiedMD5 ----
    md5_cases = []
    md5_inputs = [
        ("zeros", bytes(64), bytes(16)),
        ("pattern", bytes((i * 7 + 1) & 0xFF for i in range(64)), bytes((i * 5 + 2) & 0xFF for i in range(16))),
        ("ramp", bytes(range(64)), bytes(range(16))),
    ]
    fp = m.Fairplay3()
    for name, block, key in md5_inputs:
        out, _ = run_quiet(fp._modifiedMD5.modifiedMD5, block, key)
        md5_cases.append({"name": name, "block": hexs(block), "key": hexs(key), "out": hexs(out[:16])})

    # ---- SAPHash ----
    saphash_cases = []
    for name, size, mul, add in [("zeros", 212, 0, 0), ("pattern", 212, 7, 3), ("ramp", 212, 1, 0)]:
        block = bytes((i * mul + add) & 0xFF for i in range(size))
        out, _ = run_quiet(fp._sapHash.hash, block)
        saphash_cases.append({"name": name, "block": hexs(block), "out": hexs(out)})

    # ---- generate_session_key ----
    session_cases = []
    msg_inputs = [
        ("mode0", bytes((i * 7 + 11) & 0xFF for i in range(164))),
        ("mode3", bytes((i * 3 + 29) & 0xFF for i in range(164))),
    ]
    # 强制 mode 字节，保证两种分支都被覆盖。
    msg0 = bytearray(msg_inputs[0][1]); msg0[12] = 0
    msg3 = bytearray(msg_inputs[1][1]); msg3[12] = 3
    for name, msg in [("mode0", bytes(msg0)), ("mode3", bytes(msg3))]:
        decrypted, _ = run_quiet(fp.decryptMessage, msg)
        session_key = bytearray(m.initial_session_key)
        rounds = []
        # 逐轮复刻 generate_session_key 的中间值，便于 Rust 单测定位。
        new_sap = bytearray(m.static_source_1) + bytearray(decrypted)
        new_sap += bytearray(m.default_sap[0x80:0x80 + 0x80])
        new_sap += bytearray(m.static_source_2)
        for r in range(5):
            base = new_sap[r * 64:(r * 64) + 64]
            md5, _ = run_quiet(fp._modifiedMD5.modifiedMD5, bytes(base), bytes(session_key))
            hashed, _ = run_quiet(fp._sapHash.hash, bytes(base))
            deref_md5 = list(int.from_bytes(bytes(md5[i:i + 4]), "little") for i in range(0, 16, 4))
            deref_sap = list(int.from_bytes(bytes(hashed[i:i + 4]), "little") for i in range(0, 16, 4))
            summed = [((deref_sap[i] + deref_md5[i]) & 0xFFFFFFFF) for i in range(4)]
            session_key = bytearray(b"".join(v.to_bytes(4, "little") for v in summed))
            rounds.append({
                "base": hexs(base),
                "md5": hexs(md5[:16]),
                "hash": hexs(hashed),
                "session": hexs(session_key),
            })
        final, _ = run_quiet(fp.generate_session_key, m.default_sap, msg)
        # generate_session_key 的收尾：组内反序 + XOR 0x79。
        swapped = bytearray(session_key)
        for i in range(0, 16, 4):
            swapped[i], swapped[i + 3] = swapped[i + 3], swapped[i]
            swapped[i + 1], swapped[i + 2] = swapped[i + 2], swapped[i + 1]
        xored = bytearray(b ^ 121 for b in swapped)
        if bytes(xored) != bytes(final):
            raise SystemExit(f"session key 复刻不一致 ({name})")
        session_cases.append({
            "name": name,
            "message": hexs(msg),
            "decrypted": hexs(decrypted),
            "rounds": rounds,
            "final": hexs(final),
        })

    # ---- decryptAESKey ----
    aes_cases = []
    # 真实 /fp-setup seq3 的 164 字节请求体形态：FPLY + 版本/类型/序号。
    # 用它跑一条端到端向量，确保“端点保存的请求体”能直接喂给 decryptAESKey。
    seq3 = bytearray(164)
    seq3[0:4] = b"FPLY"
    seq3[4] = 3
    seq3[5] = 1
    seq3[6] = 3
    for i in range(16, 164):
        seq3[i] = (i * 7 + 11) & 0xFF
    seq3[12] = 0
    for name, msg, cipher in [
        ("mode0", bytes(msg0), bytes((i * 9 + 4) & 0xFF for i in range(72))),
        ("mode3", bytes(msg3), bytes((i * 11 + 7) & 0xFF for i in range(72))),
        ("fp-seq3", bytes(seq3), bytes((i * 5 + 13) & 0xFF for i in range(72))),
    ]:
        sap_key, _ = run_quiet(fp.generate_session_key, m.default_sap, msg)
        schedule, _ = run_quiet(fp.generate_key_schedule, sap_key)
        chunk1 = cipher[16:]
        chunk2 = cipher[56:]
        z_block = m.XOR_with_Z_Key(chunk2, 1)
        cycled, _ = run_quiet(fp.cycle, bytes(z_block), schedule)
        key_out = bytearray(bytes(cycled)[i] ^ chunk1[i] for i in range(16))
        after_x = m.XOR_with_X_Key(key_out, 1)
        after_z = m.XOR_with_Z_Key(after_x, 1)
        final, _ = run_quiet(fp.decryptAESKey, msg, cipher)
        if bytes(after_z) != bytes(final):
            raise SystemExit(f"decryptAESKey 复刻不一致 ({name})")
        sched_flat = [int(w) for row in schedule for w in row]
        aes_cases.append({
            "name": name,
            "message": hexs(msg),
            "cipher": hexs(cipher),
            "sap_key": hexs(sap_key),
            "schedule": [f"{w:08x}" for w in sched_flat],
            "z_block": hexs(z_block),
            "cycled": hexs(cycled),
            "after_chunk1": hexs(key_out),
            "after_x": hexs(after_x),
            "final": hexs(final),
        })

    def s(x: str) -> str:
        return '"' + x + '"'

    lines = [
        "//! 由 `scripts/fairplay3_export.py` 从参考实现 ap2/fairplay3.py 自动生成，请勿手改。",
        "//!",
        "//! 所有向量均为 Python 版在同一输入下的输出；`trace` 是 HandGarble 每次写 buffer",
        "//! 的 (buffer 序号, 下标, 新值) 序列。",
        "",
        "pub struct GarbleCase {",
        "    pub name: &'static str,",
        "    pub inputs: [&'static str; 5],",
        "    pub outputs: [&'static str; 5],",
        "    pub trace: &'static [&'static str],",
        "}",
        "",
        "pub struct Md5Case {",
        "    pub name: &'static str,",
        "    pub block: &'static str,",
        "    pub key: &'static str,",
        "    pub out: &'static str,",
        "}",
        "",
        "pub struct HashCase {",
        "    pub name: &'static str,",
        "    pub block: &'static str,",
        "    pub out: &'static str,",
        "}",
        "",
        "pub struct SessionRound {",
        "    pub base: &'static str,",
        "    pub md5: &'static str,",
        "    pub hash: &'static str,",
        "    pub session: &'static str,",
        "}",
        "",
        "pub struct SessionCase {",
        "    pub name: &'static str,",
        "    pub message: &'static str,",
        "    pub decrypted: &'static str,",
        "    pub rounds: [SessionRound; 5],",
        "    pub final_key: &'static str,",
        "}",
        "",
        "pub struct AesCase {",
        "    pub name: &'static str,",
        "    pub message: &'static str,",
        "    pub cipher: &'static str,",
        "    pub sap_key: &'static str,",
        "    pub schedule: [u32; 44],",
        "    pub z_block: &'static str,",
        "    pub cycled: &'static str,",
        "    pub after_chunk1: &'static str,",
        "    pub after_x: &'static str,",
        "    pub final_key: &'static str,",
        "}",
        "",
        "pub static GARBLE_CASES: &[GarbleCase] = &[",
    ]
    for c in cases:
        lines.append("    GarbleCase {")
        lines.append(f"        name: {s(c['name'])},")
        lines.append("        inputs: [" + ", ".join(s(c[k]) for k in ("b0", "b1", "b2", "b3", "b4")) + "],")
        lines.append("        outputs: [" + ", ".join(s(x) for x in c["out"]) + "],")
        lines.append("        trace: &[")
        for t in c["trace"]:
            lines.append(f"            {s(t)},")
        lines.append("        ],")
        lines.append("    },")
    lines.append("];")
    lines.append("")
    lines.append("pub static MD5_CASES: &[Md5Case] = &[")
    for c in md5_cases:
        lines.append("    Md5Case {")
        lines.append(f"        name: {s(c['name'])},")
        lines.append(f"        block: {s(c['block'])},")
        lines.append(f"        key: {s(c['key'])},")
        lines.append(f"        out: {s(c['out'])},")
        lines.append("    },")
    lines.append("];")
    lines.append("")
    lines.append("pub static SAPHASH_CASES: &[HashCase] = &[")
    for c in saphash_cases:
        lines.append("    HashCase {")
        lines.append(f"        name: {s(c['name'])},")
        lines.append(f"        block: {s(c['block'])},")
        lines.append(f"        out: {s(c['out'])},")
        lines.append("    },")
    lines.append("];")
    lines.append("")
    lines.append("pub static SESSION_CASES: &[SessionCase] = &[")
    for c in session_cases:
        lines.append("    SessionCase {")
        lines.append(f"        name: {s(c['name'])},")
        lines.append(f"        message: {s(c['message'])},")
        lines.append(f"        decrypted: {s(c['decrypted'])},")
        lines.append("        rounds: [")
        for r in c["rounds"]:
            lines.append("            SessionRound {")
            lines.append(f"                base: {s(r['base'])},")
            lines.append(f"                md5: {s(r['md5'])},")
            lines.append(f"                hash: {s(r['hash'])},")
            lines.append(f"                session: {s(r['session'])},")
            lines.append("            },")
        lines.append("        ],")
        lines.append(f"        final_key: {s(c['final'])},")
        lines.append("    },")
    lines.append("];")
    lines.append("")
    lines.append("pub static AES_CASES: &[AesCase] = &[")
    for c in aes_cases:
        lines.append("    AesCase {")
        lines.append(f"        name: {s(c['name'])},")
        lines.append(f"        message: {s(c['message'])},")
        lines.append(f"        cipher: {s(c['cipher'])},")
        lines.append(f"        sap_key: {s(c['sap_key'])},")
        lines.append("        schedule: [")
        for i in range(0, 44, 11):
            lines.append("            " + ", ".join("0x" + w for w in c["schedule"][i:i + 11]) + ",")
        lines.append("        ],")
        lines.append(f"        z_block: {s(c['z_block'])},")
        lines.append(f"        cycled: {s(c['cycled'])},")
        lines.append(f"        after_chunk1: {s(c['after_chunk1'])},")
        lines.append(f"        after_x: {s(c['after_x'])},")
        lines.append(f"        final_key: {s(c['final'])},")
        lines.append("    },")
    lines.append("];")
    lines.append("")

    return "\n".join(lines)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default="/tmp/fairplay3.py", help="参考实现路径")
    args = ap.parse_args()

    if sys.byteorder != "little":
        raise SystemExit("参考实现依赖小端序，当前机器不是 little-endian")

    m = load_module(Path(args.source))
    SRC_DIR.mkdir(parents=True, exist_ok=True)
    (SRC_DIR / "tables.rs").write_text(build_tables(m), encoding="utf-8")
    (SRC_DIR / "test_vectors.rs").write_text(build_vectors(m), encoding="utf-8")
    print(f"已写入 {SRC_DIR / 'tables.rs'} 与 {SRC_DIR / 'test_vectors.rs'}")


if __name__ == "__main__":
    main()
