//! Android / 桌面端的 C ABI 入口。
//!
//! Java 侧通过 `RustCast` 调用：`ftv_cast_start` 启动 DLNA，之后轮询
//! `ftv_cast_take_play` 拿播放请求（避免从 native 反向调 Java）。

use std::ffi::{c_char, CStr, CString};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use ftv_cast::dlna::{self, Shared, Sink, State};

struct BridgeSink;

static STATE: OnceLock<Shared> = OnceLock::new();
static RUNNING: OnceLock<Arc<AtomicBool>> = OnceLock::new();
static PENDING: OnceLock<Mutex<Option<(String, String)>>> = OnceLock::new();

fn state() -> &'static Shared {
    STATE.get_or_init(|| Arc::new(Mutex::new(State::new())))
}
fn pending() -> &'static Mutex<Option<(String, String)>> {
    PENDING.get_or_init(|| Mutex::new(None))
}

impl Sink for BridgeSink {
    fn play(&self, url: &str, title: &str) {
        log::info!("play {url}");
        *pending().lock().unwrap() = Some((url.to_string(), title.to_string()));
    }
    fn stop(&self) {
        let mut st = state().lock().unwrap();
        st.transport = "STOPPED".into();
    }
    fn seek(&self, s: u64) {
        state().lock().unwrap().position = s;
    }
    fn pause(&self) {
        state().lock().unwrap().transport = "PAUSED_PLAYBACK".into();
    }
    fn resume(&self) {
        state().lock().unwrap().transport = "PLAYING".into();
    }
    fn set_volume(&self, v: u8) {
        state().lock().unwrap().volume = v;
    }
}

fn cstr(p: *const c_char) -> String {
    if p.is_null() {
        return String::new();
    }
    unsafe { CStr::from_ptr(p) }.to_string_lossy().to_string()
}

/// 启动 DLNA 接收端，返回 0 表示成功。
#[no_mangle]
pub extern "C" fn ftv_cast_start(
    port: u16,
    ip: *const c_char,
    udn: *const c_char,
    name: *const c_char,
) -> i32 {
    let ip = cstr(ip);
    let udn = cstr(udn);
    let name = cstr(name);
    let running = RUNNING.get_or_init(|| Arc::new(AtomicBool::new(true))).clone();
    if !running.load(Ordering::Relaxed) {
        return -1;
    }
    log::info!("ftv_cast_start {ip}:{port} {name}");
    ftv_cast::ssdp::spawn(ip.clone(), port, udn.clone(), running.clone());
    match ftv_cast::http::spawn(
        port,
        udn,
        name,
        state().clone(),
        Arc::new(BridgeSink),
        running,
    ) {
        Ok(()) => 0,
        Err(e) => {
            log::error!("http bind failed: {e}");
            -2
        }
    }
}

/// 取一次播放请求（"url\ntitle"），没有就返回 NULL。用完要调 `ftv_cast_free`。
#[no_mangle]
pub extern "C" fn ftv_cast_take_play() -> *mut c_char {
    let taken = pending().lock().unwrap().take();
    match taken {
        Some((url, title)) => CString::new(format!("{url}\n{title}"))
            .map(|s| s.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        None => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "C" fn ftv_cast_free(p: *mut c_char) {
    if !p.is_null() {
        unsafe {
            let _ = CString::from_raw(p);
        }
    }
}

/// 播放器回报进度/状态，供手机查询。
#[no_mangle]
pub extern "C" fn ftv_cast_set_progress(duration: u64, position: u64, playing: i32) {
    let mut st = state().lock().unwrap();
    st.duration = duration;
    st.position = position;
    st.transport = if playing != 0 { "PLAYING" } else { "PAUSED_PLAYBACK" }.into();
}

#[no_mangle]
pub extern "C" fn ftv_cast_set_stopped() {
    let mut st = state().lock().unwrap();
    st.transport = "STOPPED".into();
}

#[no_mangle]
pub extern "C" fn ftv_cast_stop() {
    if let Some(r) = RUNNING.get() {
        r.store(false, Ordering::Relaxed);
    }
}

#[cfg(target_os = "android")]
mod jni_bridge {
    use jni::objects::{JClass, JString};
    use jni::sys::{jint, jlong, jstring};
    use jni::JNIEnv;
    use std::ffi::{c_char, CStr, CString};

    fn c(s: &str) -> CString {
        CString::new(s).unwrap_or_default()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_start(
        mut env: JNIEnv,
        _class: JClass,
        port: jint,
        ip: JString,
        udn: JString,
        name: JString,
    ) -> jint {
        let ip: String = env.get_string(&ip).map(Into::into).unwrap_or_default();
        let udn: String = env.get_string(&udn).map(Into::into).unwrap_or_default();
        let name: String = env.get_string(&name).map(Into::into).unwrap_or_default();
        let (cip, cudn, cname) = (c(&ip), c(&udn), c(&name));
        super::ftv_cast_start(
            port as u16,
            cip.as_ptr() as *const c_char,
            cudn.as_ptr() as *const c_char,
            cname.as_ptr() as *const c_char,
        )
    }

    #[no_mangle]
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_takePlay(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        let p = super::ftv_cast_take_play();
        if p.is_null() {
            return std::ptr::null_mut();
        }
        let s = unsafe { CStr::from_ptr(p) }.to_string_lossy().to_string();
        super::ftv_cast_free(p);
        match env.new_string(s) {
            Ok(js) => js.into_raw(),
            Err(_) => std::ptr::null_mut(),
        }
    }

    #[no_mangle]
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_setProgress(
        _env: JNIEnv,
        _class: JClass,
        duration: jlong,
        position: jlong,
        playing: jint,
    ) {
        super::ftv_cast_set_progress(duration as u64, position as u64, playing);
    }

    #[no_mangle]
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_setStopped(
        _env: JNIEnv,
        _class: JClass,
    ) {
        super::ftv_cast_set_stopped();
    }

    #[no_mangle]
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_stop(
        _env: JNIEnv,
        _class: JClass,
    ) {
        super::ftv_cast_stop();
    }
}
