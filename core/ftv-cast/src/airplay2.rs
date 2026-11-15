//! AirPlay 2：HAP 配对（X25519 / Ed25519 / ChaCha20-Poly1305）+ buffered audio。
//! 依赖的密码学实现稍后接入（Rust 生态比 Java 完整）。

/// AirPlay 2 的 RTSP 端口（设备上 7000/7100 常被其他投屏 App 占用，实际用随机端口）。
pub const DEFAULT_PORT: u16 = 7000;
