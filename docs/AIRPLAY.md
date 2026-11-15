# AirPlay 2 实现笔记

记录从「iOS 完全连不上」到「能出声」过程中确认的事实，避免以后重复踩坑。

## 协议流程（iOS 17 实测）

```
1. mDNS 发现        _airplay._tcp（设备名、features、pk=Ed25519 32B hex、deviceid=MAC 格式）
2. POST /fp-setup   FairPlay 3，X-Apple-ET: 32；分两包（seq=1 / seq=3）
3. POST /pair-setup HAP SRP-6a（3072-bit, SHA-512），transient 模式
4. POST /pair-verify X25519 + Ed25519 + ChaCha20-Poly1305，建立会话
5. POST /setup      plist 协商音频流（streams/shk 等）
6. POST /audio      buffered audio（加密流）→ ALAC/AAC 解码 → 出声
```

关键：**iOS 17 不再支持 AirPlay 1（RAOP）**。只广播 `_raop._tcp` 时，iPhone 只发两三个
`OPTIONS` 就静默放弃，永远不发 `ANNOUNCE`。必须走 AirPlay 2。

## FairPlay

| 版本 | 用法 | 实现 |
|---|---|---|
| FairPlay 1 | AirPlay 1 的 `/fp-setup`，4 组固定 142 字节响应（按请求第 14 字节选） | `fairplay.rs`（仅作参考，iOS 17 不用） |
| **FairPlay 3** | AirPlay 2 的 `/fp-setup`（`X-Apple-ET: 32`），响应由请求现场算出 | `fairplay3/`（移植自 openairplay/airplay2-receiver） |

要点：

- 响应**不是固定表**。开源 `playfair.py` 里的 4 组 142 字节是绑定特定 Apple 密钥对的样本，
  直接抄会被 iOS 拒绝（实测对比 AirReceiver 的响应，第 13 字节起就不同）。
- `/fp-setup` seq=3 的请求体（164 字节）要保存为 `message3`，后面解 72 字节 `fpaeskey` 时用。
- 参考实现：`openairplay/airplay2-receiver` 的 `ap2/fairplay3.py`（约 6170 行，其中大部分是常量表）。
  移植时用脚本导出常量，并用 Python 版的中间值做对照单测（见 `core/ftv-cast/scripts/fairplay3_export.py`）。

## mDNS TXT（与可用设备对齐后的值）

```
_airplay._tcp
  deviceid=AA:BB:CC:DD:EE:FF      # 必须是 MAC 格式
  features=0x527FFEF7,0x8         # TXT 里是「低32位,高32位」
  flags=0x4
  model=AppleTV3,1
  rmodel=AirReceiver3,1
  srcvers=211.3
  pw=0
  pk=<64 hex，Ed25519 公钥>
  pi=<uuid>
```

`/info` 返回的 plist 里 `features` 是 **64 位整数**，高字在前：
`(0x8 << 32) | 0x527FFEF7 = 0x8527FFEEF7`（拼反会被拒）。

## RTSP 细节

- `/info`、`/fp-setup`、`/pair-*` 都是 **RTSP 报文**（不是 HTTP）：响应必须是 `RTSP/1.0` 且回显 `CSeq`
- 请求可能带 body（`GET /info` 带 70 字节 plist），必须先按 `Content-Length` 读完再路由
- `/info` 必须是二进制 plist（`bplist00`），Content-Type `application/x-apple-binary-plist`

## 音频

- 盒子 `media_codecs.xml` 声明了 `audio/alac`，但 `/system/lib` **没有** `libstagefright_soft_alacdec.so`，
  `MediaCodec.createDecoderByType("audio/alac")` 会失败 → 自带 Apple ALAC 解码器（`app/src/main/jni/alac`）
- ALAC 解码器验证方法：`ffmpeg` 生成 ALAC，按包切帧，和 `ffmpeg -f s16le` 的输出**逐字节比对**
- 端口 7000/7100 常被 AirReceiver 占用，AirPlay 2 用随机端口并广播真实端口

## 调试经验

- 先给 Rust 接 `android_logger`（tag `FtvCast`），否则"没日志"会误判成"没请求"
- 抓包是最快的定位手段：iPhone 的第一条请求是 `/fp-setup`，不是 `/info`
- `SwitchAudioSource`（macOS）**不会列出**局域网 AirPlay 设备，不能用来验证接收端
- 用 `dns-sd -L` 对比同网可用设备的 TXT，是最省事的对齐方式
