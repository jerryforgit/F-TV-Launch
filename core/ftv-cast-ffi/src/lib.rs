//! Android / 桌面端的 C ABI 入口。

use std::os::raw::c_char;

/// 启动协议核心（DLNA + AirPlay），返回 0 表示成功。
#[no_mangle]
pub extern "C" fn ftv_cast_start(_name: *const c_char) -> i32 {
    log::info!("ftv-cast core start");
    0
}

#[no_mangle]
pub extern "C" fn ftv_cast_stop() {
    log::info!("ftv-cast core stop");
}
