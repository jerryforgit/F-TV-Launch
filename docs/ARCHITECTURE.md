# 架构总览

F TV 桌面 = **Android 桌面 UI（Java）** + **投屏协议核心（Rust）**，两者通过 JNI 连接。

```
┌──────────────────────────── Android 进程 ────────────────────────────┐
│  Java                                                  Rust (.so)     │
│  ┌───────────────┐   JNI    ┌──────────────────┐                    │
│  │ MainActivity  │◄────────►│ libftv_cast_ffi  │                    │
│  │ AllApps       │          │  ├ ftv-cast      │                    │
│  │ DlnaPlayer    │          │  │  ├ dlna       │  SSDP 1900         │
│  │ Settings/SMB  │          │  │  ├ http       │  DLNA 8090         │
│  │ Remote/Control│          │  │  ├ mdns       │  mDNS 5353         │
│  └───────────────┘          │  │  ├ airplay2   │  RTSP 5000         │
│                             │  │  ├ fairplay3  │                    │
│                             │  │  ├ srp/tlv8   │                    │
│                             │  └ audio        │                    │
│                             └──────────────────┘                    │
└──────────────────────────────────────────────────────────────────────┘
```

## 端口一览

| 端口 | 用途 | 进程 |
|---|---|---|
| 8089 | 手机遥控 / 传文件网页 / 配对 API | Java `ControlServer` |
| 8090 | DLNA 设备描述 + SOAP 控制 | Rust `ftv-cast` |
| 5000 | AirPlay 2 RTSP（fp-setup / pair-setup / pair-verify / setup） | Rust `ftv-cast` |
| 5353 | mDNS 广播（`_airplay._tcp` / `_raop._tcp`） | Rust / Java 播音器 |
| 1900 | SSDP 发现（DLNA） | Rust `ftv-cast::ssdp` |

> 7000/7100 常被盒子上其它投屏 App（AirReceiver）占用，所以 AirPlay 2 端口是动态的，通过 mDNS 广播真实端口；DLNA 固定 8090。

## 目录结构

```
app/src/main/java/com/jerry/ftvlaunch/     Android UI 与平台能力
  MainActivity.java        桌面网格、顶栏、焦点管理
  AllAppsActivity.java     应用抽屉（整行滚动、高亮）
  DlnaPlayerActivity.java  DLNA/AirPlay 播放页（视频/图片 + 进度条）
  ControlServer.java       8089：遥控 API + PWA 页面 + 传文件
  RemoteA11yService.java   无障碍注入（其他 App 的按键）
  CursorOverlay.java       体感遥控悬浮光标
  RustCast.java / RustDlna.java  JNI 封装 + 播放事件轮询
  ...

app/src/main/jni/alac/     Apple ALAC 解码器（NDK，用于 AirPlay 音频）
app/src/main/assets/jsqr.js  手机端拍照识别二维码

core/ftv-cast/src/         平台无关协议核心
  dlna.rs                 设备描述、SOAP 动作、状态机
  ssdp.rs                 SSDP 发现应答
  http.rs                 DLNA 的 HTTP/SCPD/SOAP
  mdns.rs                 长 TXT 拆段（AirPlay pk 512 字符）
  tlv8.rs                 HAP 报文
  crypto.rs               X25519 / Ed25519 / HKDF / ChaCha20-Poly1305
  srp.rs                  SRP-6a（3072-bit, SHA-512）
  pairing.rs              pair-setup / pair-verify 状态机
  airplay2_http.rs        /info、/fp-setup、/pair-* 端点
  fairplay.rs             FairPlay 1 查表（AirPlay 1，仅供参考）
  fairplay3/              FairPlay 3 移植（AirPlay 2）
core/ftv-cast-ffi/src/lib.rs  C ABI + JNI 桥
```

## 数据/控制流

**DLNA 投屏**
1. 手机 SSDP 搜索 → Rust 应答 → 拉取 `/desc.xml`
2. 手机 POST `/control/avt`（SetAVTransportURI）→ Rust 入队 `play` 事件
3. Java `RustDlna` 轮询 `ftv_cast_take_event()` → 拉起 `DlnaPlayerActivity`
4. 播放页每 500ms 调 `ftv_cast_set_progress()` 回报进度，手机可查询
5. 手机发 Pause/Seek/Stop → Rust 入队事件 → Java 执行

**AirPlay 2 音频**
`/fp-setup`(FairPlay 3) → `/pair-setup`(SRP) → `/pair-verify`(X25519+Ed25519) → `/setup` → `/audio`（解密后送 ALAC 解码 / AudioTrack）

**手机遥控**
`ControlServer` 8089 提供 PWA；按键优先直接投递给前台 Activity（`App.current`），其他 App 走无障碍，必要时用 root（盒子无 su，实际是 a11y）。

## 关键设计决定

- **遥控器优先**：所有界面按 D-pad 设计，不用触摸交互；不给用户看"触摸提示"。
- **投屏协议用 Rust**：可复用到 Windows/Linux；加密生态（X25519/Ed25519/ChaCha20）比 Java 完整。
- **DLNA 自研**：可控、可测；AirPlay 的 FairPlay 属于逆向，仅个人使用。
- **解码器自带**：盒子声明了 `audio/alac` 但**没有**实际解码库，所以 NDK 编译 Apple ALAC 解码器。
- **配对码 6 位数字**：简单好输，60 秒内错 15 次临时锁定。
