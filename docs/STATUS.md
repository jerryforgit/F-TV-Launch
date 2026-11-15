# 项目现状（交接 / 下次开工先看这里）

## 一句话

Android TV 桌面（Java）+ 自研投屏核心（Rust）：**DLNA 完整可用**，**AirPlay 2 已能出声**（持续完善中）。

## 已完成

### 桌面（Java）
- 1:1 还原旧桌面：图标网格、顶栏 7 入口、时钟、深浅主题、背景图
- 应用抽屉：整行滚动、焦点高亮、标题不被遮挡、首行放大不被裁切
- 长按菜单：置顶 / 移动 / 卸载 / 开机启动 / 设壁纸
- 空位、整理桌面、小组件（含 MENU 菜单）
- 扫码传文件（PWA + 应用内安装/设背景）、SMB 浏览、图片选择器
- 手机遥控 PWA（8089）：配对码、方向/OK/返回/主页、文字输入、应用列表、**链接投屏**、体感光标（陀螺仪）
- 返回键在桌面不退出；`UP` 到顶栏时网格取消高亮

### DLNA（Rust，`core/ftv-cast`）
- SSDP 发现 + 设备描述 + SCPD + SOAP（AVTransport / RenderingControl / ConnectionManager）
- 播放页：视频/图片、进度条、遥控操作、进度回报给手机
- B 站、QQ 音乐（QPlay）、链接投屏 实测通过
- 已知限制：系统 `MediaPlayer` 解码，MKV/HEVC/DRM 可能失败；HLS 兼容性一般

### AirPlay 2（Rust）
- mDNS `_airplay._tcp`（TXT 与可用设备对齐，`pk` 为 Ed25519）
- `/info`（二进制 plist，19 个字段）、RTSP 响应 + CSeq
- `/fp-setup`（**FairPlay 3**，移植自 openairplay，`core/ftv-cast/src/fairplay3/`）
- `/pair-setup`（SRP-6a 3072/SHA-512，口令 `Pair-Setup`/`3939`）
- `/pair-verify`（X25519 + Ed25519 + ChaCha20-Poly1305，含验签）
- `/setup`：按**真实抓包**实现（`ekey`/`eiv` 解密 + `streams` 端口协商）— `d79d8e5`
- `/audio`：实时 UDP 音频解密（AES-CBC）+ ALAC 解码 + AudioTrack 出声 — `f3068de`（**已出声**）
- 当前正在完善：延迟/同步、断连重连、音量与元数据、稳定性

### 测试
- `cargo test`：40+ 单测（TLV8、密码学、SRP、配对、FairPlay3、DLNA、mDNS）
- ALAC：与 ffmpeg 解码结果逐字节比对通过
- 脚本测试：SOAP、`/info`、mDNS、抓包（见 `docs/TESTING.md`）

## 未完成 / 待办

1. **AirPlay 2 收尾**：`/setup` 字段解析、音频延迟/同步、断连重连、音量/元数据
2. **DLNA 兼容性**：必要时引入 ExoPlayer 兜底（HLS/MKV/HEVC）
3. **投屏入口**：顶栏「投屏」按钮（唤起内置能力）——可选，因为现在自研已可用
4. **文档**：README 补投屏章节与架构链接
5. **清理**：Java 版旧 `DlnaServer`（已被 Rust 取代）可在稳定后删除；`fairplay.rs`（FP1 表）仅参考

## 关键约束（务必遵守）

- **不改 UI 风格**：遥控器优先，触摸提示不要加到电视界面
- **改动必须可自测**：协议层优先写单测；界面用截图验证
- **Rust 改动记得重编 native**，否则盒子跑的是旧 `.so`
- **端口冲突**：7000/7100 被 AirReceiver 占用，别硬编码
- **FairPlay 仅个人使用**，不对外分发（仓库只开源 DLNA 部分）

## 常用命令速查

```bash
# 构建
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/27.0.12077973
(cd core && cargo ndk -t armeabi-v7a -t arm64-v8a --platform 21 -o ../app/src/main/jniLibs build --release -p ftv-cast-ffi)
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=/tmp/gh <gradle> --no-daemon assembleDebug

# 安装 & 日志
adb connect 192.168.10.155:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s FtvCast AirPlay AlacJni

# 快照
adb exec-out screencap -p > /tmp/shot.png
```

## 仓库

- 远程：`git@github.com:jerryforgit/F-TV-Launch.git`（SSH，HTTPS 推不了）
- 分支：`main`
- 备份 APK：`~/box-backup/ftvlauncher-v*.apk`
