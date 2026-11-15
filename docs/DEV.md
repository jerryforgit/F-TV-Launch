# 开发与调试

## 环境

| 项 | 值 |
|---|---|
| 项目 | `~/Syncthing/repos/f_tv_launch` |
| 包名 | `com.jerry.ftvlaunch` |
| 盒子 | `192.168.10.155`（Ugoos X4，Android 11，1920×1080，**armeabi-v7a**） |
| Mac | `192.168.10.113`（网卡可能是 `en5`，不是 en0） |
| JDK | 17（`/usr/libexec/java_home -v 17`） |
| Gradle | `~/.gradle/wrapper/dists/gradle-8.11.1-bin/bpt9gzteqjrbo1mjrsrsomdt32c/gradle-8.11.1/bin/gradle` |
| Gradle 缓存 | `GRADLE_USER_HOME=/tmp/gh`（绕过损坏的全局代理配置） |
| NDK | `27.0.12077973`（26.1 缺 `source.properties`，不可用） |
| Rust | 1.99，target：`armv7-linux-androideabi`、`aarch64-linux-android` |
| cargo-ndk | 4.x |
| 代理 | `export http_proxy=http://127.0.0.1:7890 https_proxy=...`（rustup / crates.io / GitHub 需要） |

## 构建

```bash
cd ~/Syncthing/repos/f_tv_launch

# 1) 有 Rust 改动时：编 native 库（产物进 app/src/main/jniLibs，已被 .gitignore）
export ANDROID_NDK_HOME=~/Library/Android/sdk/ndk/27.0.12077973
(cd core && cargo ndk -t armeabi-v7a -t arm64-v8a --platform 21 \
   -o ../app/src/main/jniLibs build --release -p ftv-cast-ffi)

# 2) 编 APK
JAVA_HOME=$(/usr/libexec/java_home -v 17) GRADLE_USER_HOME=/tmp/gh \
  <gradle> --no-daemon assembleDebug

# 3) 装到盒子
adb connect 192.168.10.155:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 只改 Rust、没改 Java 时，Gradle 可能因为 native 中间产物缓存而不更新 `.so`。
> 遇到"改了没生效"，先删：
> `rm -rf app/build/intermediates/merged_native_libs app/build/intermediates/stripped_native_libs core/target`

仅测 Rust：

```bash
cd core && cargo test          # 当前 40+ 单测
```

## 盒子常用命令

```bash
A=192.168.10.155:5555
adb -s $A shell 'am force-stop com.jerry.ftvlaunch; input keyevent KEYCODE_HOME'
adb -s $A shell 'am start -n com.jerry.ftvlaunch/.MainActivity'
adb -s $A exec-out screencap -p > /tmp/shot.png
adb -s $A logcat -d -s FtvCast AirPlay AlacJni | tail -20

# 无障碍（force-stop 后系统会清掉，需重开）
adb -s $A shell 'settings put secure enabled_accessibility_services \
  com.jerry.ftvlaunch/com.jerry.ftvlaunch.RemoteA11yService; \
  settings put secure accessibility_enabled 1'

# 悬浮窗（体感光标）
adb -s $A shell 'appops set com.jerry.ftvlaunch SYSTEM_ALERT_WINDOW allow'

# 设为桌面
adb -s $A shell 'cmd package set-home-activity com.jerry.ftvlaunch'
```

数据/配置：

```bash
adb shell 'run-as com.jerry.ftvlaunch cat shared_prefs/remote.xml'   # 6 位配对码
adb shell 'run-as com.jerry.ftvlaunch cat files/home.json'           # 桌面布局
```

## 测试手段（不需要手机）

**DLNA（SOAP 直连）**

```bash
curl -s http://192.168.10.155:8090/desc.xml | head -c 300
# SetAVTransportURI 等见 docs/TESTING.md
```

**AirPlay 端点**

```bash
# /info
python3 - <<'PY'
import socket, plistlib
body=plistlib.dumps({"qualifier":["txtAirPlay"]}, fmt=plistlib.FMT_BINARY)
req=(b"GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Type: application/x-apple-binary-plist\r\n"
     b"Content-Length: "+str(len(body)).encode()+b"\r\n\r\n"+body)
s=socket.create_connection(("192.168.10.155",5000), timeout=5); s.sendall(req)
print(s.recv(200))
PY
```

**mDNS**

```bash
dns-sd -B _airplay._tcp local.
dns-sd -L "F TV 桌面" _airplay._tcp local.     # 看 TXT（features/model/pk）
```

**抓包（盒子）**

```bash
adb root
adb shell 'tcpdump -i wlan0 -s0 -G 180 -W 1 -w /sdcard/ap.pcap "port 5000 or port 7000 or port 5353"'
adb pull /sdcard/ap.pcap /tmp/ap.pcap
```

> 交换机网络下，Mac 上的 tcpdump 只能看到 mDNS 和自己的流量，抓不到手机↔盒子单播。

## 已知坑

| 现象 | 原因/处理 |
|---|---|
| 改了 Rust 没生效 | native 中间产物缓存，见上面的清理命令 |
| 无障碍开关自己关了 | `am force-stop` 会清掉，重新 `settings put` |
| iOS 看不到设备 | 确认 `_airplay._tcp` TXT 有 `pk`（64 hex）、`features`、`deviceid` |
| AirPlay 只发 `OPTIONS` 就停 | iOS 17 不支持 AirPlay 1，需要 AirPlay 2（FairPlay 3） |
| 投屏从头开始 / 拖进度显示结束 | B 站先 SetURI 再单独 Seek；播放页有 350ms 起播窗口 + EOS 防抖 |
| 配对码被意外重置 | 「重新生成配对码」已加二次确认 |
