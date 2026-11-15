//! AirPlay 2：HAP 配对（X25519 / Ed25519 / ChaCha20-Poly1305）+ buffered audio。
//! 依赖的密码学实现稍后接入（Rust 生态比 Java 完整）。

/// AirPlay 2 的 RTSP 端口（设备上 7000/7100 常被其他投屏 App 占用，实际用随机端口）。
pub const DEFAULT_PORT: u16 = 7000;

/// HAP pair-setup 的用户名与默认口令（参考实现未配置密码时用 3939）。
pub const PAIRING_USER: &[u8] = b"Pair-Setup";
pub const PAIRING_PASSWORD: &[u8] = b"3939";

/// AirPlay 2 在 `_airplay._tcp` 里声明的能力位。
/// bit 38 = AirPlay 音频（AirPlay 2），bit 27 = 支持 legacy pairing。
pub const FEATURES: u64 = (1 << 38) | (1 << 27) | (1 << 9) | (1 << 8);
