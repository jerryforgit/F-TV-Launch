//! Android / 桌面端的 C ABI 入口。
//!
//! Java 侧通过 `RustCast` 调用：`ftv_cast_start` 启动 DLNA，之后轮询
//! `ftv_cast_take_play` 拿播放请求（避免从 native 反向调 Java）。

use std::ffi::{c_char, CStr, CString};
use std::sync::atomic::{AtomicBool, Ordering};
use std::collections::VecDeque;
use std::sync::{Arc, Mutex, OnceLock};

use ftv_cast::dlna::{self, Shared, Sink, State};

struct BridgeSink;

static STATE: OnceLock<Shared> = OnceLock::new();
static RUNNING: OnceLock<Arc<AtomicBool>> = OnceLock::new();
static EVENTS: OnceLock<Mutex<VecDeque<String>>> = OnceLock::new();
static IDENTITY_PK: OnceLock<String> = OnceLock::new();

fn state() -> &'static Shared {
    STATE.get_or_init(|| Arc::new(Mutex::new(State::new())))
}
fn events() -> &'static Mutex<VecDeque<String>> {
    EVENTS.get_or_init(|| Mutex::new(VecDeque::new()))
}

fn push(event: String) {
    let mut q = events().lock().unwrap();
    if q.len() > 32 {
        q.pop_front();
    }
    q.push_back(event);
}

impl Sink for BridgeSink {
    fn play(&self, url: &str, title: &str) {
        log::info!("play {url}");
        push(format!("play\n{url}\n{title}"));
    }
    fn stop(&self) {
        state().lock().unwrap().transport = "STOPPED".into();
        push("stop".into());
    }
    fn seek(&self, s: u64) {
        state().lock().unwrap().position = s;
        push(format!("seek\n{s}"));
    }
    fn pause(&self) {
        state().lock().unwrap().transport = "PAUSED_PLAYBACK".into();
        push("pause".into());
    }
    fn resume(&self) {
        state().lock().unwrap().transport = "PLAYING".into();
        push("resume".into());
    }
    fn set_volume(&self, v: u8) {
        state().lock().unwrap().volume = v;
        push(format!("volume\n{v}"));
    }
}

#[cfg(target_os = "android")]
fn init_log() {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        android_logger::init_once(
            android_logger::Config::default()
                .with_tag("FtvCast")
                .with_max_level(log::LevelFilter::Debug),
        );
    });
}

#[cfg(not(target_os = "android"))]
fn init_log() {}

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
    init_log();
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

/// 取一条控制事件："play\nurl\ntitle" / "stop" / "pause" / "resume" / "seek\n秒" / "volume\n值"。
/// 没有就返回 NULL；用完要调 `ftv_cast_free`。
#[no_mangle]
pub extern "C" fn ftv_cast_take_event() -> *mut c_char {
    let taken = events().lock().unwrap().pop_front();
    match taken {
        Some(e) => CString::new(e)
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

/// 启动 AirPlay 2 配对端点，返回端口（<0 表示失败）。
#[no_mangle]
/// 返回设备 Ed25519 公钥（hex），供 `_airplay._tcp` 的 pk 使用。
#[no_mangle]
pub extern "C" fn ftv_cast_identity_pk() -> *mut c_char {
    let pk = IDENTITY_PK.get_or_init(|| {
        let (_sk, pk) = ftv_cast::crypto::ed25519_keypair();
        pk.iter().map(|b| format!("{b:02x}")).collect::<String>()
    });
    CString::new(pk.clone()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "C" fn ftv_cast_start_airplay2() -> i32 {
    init_log();
    let port = ftv_cast::airplay2_http::spawn(5000)
        .or_else(|_| ftv_cast::airplay2_http::spawn(0));
    match port {
        Ok(port) => {
            log::info!("airplay2 pairing on {port}");
            port as i32
        }
        Err(e) => {
            log::error!("airplay2 bind failed: {e}");
            -1
        }
    }
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
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_takeEvent(
        mut env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        let p = super::ftv_cast_take_event();
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
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_identityPk(
        env: JNIEnv,
        _class: JClass,
    ) -> jstring {
        let p = super::ftv_cast_identity_pk();
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
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_startAirplay2(
        _env: JNIEnv,
        _class: JClass,
    ) -> jint {
        super::ftv_cast_start_airplay2()
    }

    #[no_mangle]
    pub extern "system" fn Java_com_jerry_ftvlaunch_RustCast_stop(
        _env: JNIEnv,
        _class: JClass,
    ) {
        super::ftv_cast_stop();
    }
}
