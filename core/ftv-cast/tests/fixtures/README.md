# AirPlay 2 抓包 fixture

来源：`/tmp/ap3.pcap`（2026-11-15，iPhone `192.168.10.114` → AirReceiver
`192.168.10.155:7000` 的完整成功流程）。

| 文件 | 内容 |
| --- | --- |
| `ap3_fp_seq3.bin` | `POST /fp-setup` 第 2 包（seq 3）的 164 字节 FPLY 数据块，即 `decrypt_aes_key` 的 `message3` |
| `ap3_setup1.bplist` | 第一个 `SETUP` 的二进制 plist：`ekey`（72 字节）、`eiv`（16 字节）等 |
| `ap3_setup2.bplist` | 第二个 `SETUP` 的二进制 plist：`streams`（type 96 / controlPort / audioFormat 等） |

用这三个文件可以还原真实的 FairPlay AES key：

```text
ekey + ap3_fp_seq3.bin -> 38c9b755988270948b207e9e67ee18ff
eiv                    -> 4be9c99bf6fe53b84e26c0c33198ee58
```

注意：该抓包只有 5000（配对）和 7000（控制）两条 TCP 流，没有音频数据连接，
所以 `/audio` 的 RTP 解密无法用这个 pcap 做真实向量，需要重新抓一个纯音频
（Apple Music / 播客）的包。
