//! F TV 投屏协议核心：DLNA / AirPlay 1 (RAOP) / AirPlay 2 (HAP + buffered audio)。
//!
//! 平台无关；Android 通过 `ftv-cast-ffi` 的 C ABI 调用，桌面端直接依赖本 crate。

pub mod airplay2;
pub mod dlna;
pub mod http;
pub mod mdns;
pub mod ssdp;

/// 一次投屏会话的公共状态。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CastState {
    Stopped,
    Playing,
    Paused,
}
