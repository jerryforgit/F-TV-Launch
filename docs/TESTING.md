# 测试手册

## 1. Rust 单测

```bash
cd core && cargo test
```

覆盖：TLV8、X25519/Ed25519/HKDF/ChaCha20、SRP-6a、pair-setup、pair-verify、FairPlay3、
DLNA 描述/SOAP/状态机、mDNS 长 TXT 拆段、ALAC（C 侧另有 ffmpeg 对照）。

## 2. DLNA（脚本模拟手机）

```bash
python3 - <<'PY'
import urllib.request, html, time, re
BASE='http://192.168.10.155:8090'
P={'AVTransport':'avt','RenderingControl':'rcs','ConnectionManager':'cms'}
def soap(svc, action, inner=''):
    body=f'''<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
<s:Body><u:{action} xmlns:u="urn:schemas-upnp-org:service:{svc}:1">{inner}</u:{action}></s:Body></s:Envelope>'''
    req=urllib.request.Request(BASE+'/control/'+P[svc], data=body.encode(),
        headers={'Content-Type':'text/xml','SOAPAction':f'"urn:schemas-upnp-org:service:{svc}:1#{action}"'})
    return urllib.request.urlopen(req, timeout=6).read().decode()
def one(x,t):
    m=re.search('<'+t+'>(.*?)</'+t+'>',x); return m.group(1) if m else None

soap('AVTransport','SetAVTransportURI',
     '<InstanceID>0</InstanceID><CurrentURI>http://192.168.10.113:8000/test.mp4</CurrentURI>'
     '<CurrentURIMetaData></CurrentURIMetaData>')
time.sleep(2); soap('AVTransport','Play','<InstanceID>0</InstanceID><Speed>1</Speed>')
time.sleep(3)
print('state', one(soap('AVTransport','GetTransportInfo','<InstanceID>0</InstanceID>'),'CurrentTransportState'))
print('pos  ', one(soap('AVTransport','GetPositionInfo','<InstanceID>0</InstanceID>'),'RelTime'))
soap('AVTransport','Pause','<InstanceID>0</InstanceID>')
soap('AVTransport','Seek','<InstanceID>0</InstanceID><Unit>REL_TIME</Unit><Target>00:00:30</Target>')
soap('AVTransport','Stop','<InstanceID>0</InstanceID>')
PY
```

本机起测试流：

```bash
ffmpeg -y -f lavfi -i "testsrc=size=1280x720:rate=25:duration=90" \
  -f lavfi -i "sine=frequency=440:duration=90" -c:v libx264 -profile:v main \
  -pix_fmt yuv420p -c:a aac -movflags +faststart /tmp/dlna/test.mp4
cd /tmp/dlna && python3 -m http.server 8000
```

> MP4 要加 `-movflags +faststart`，否则部分盒子播放器会报错（moov 在文件尾）。

## 3. AirPlay 2

发现：

```bash
dns-sd -B _airplay._tcp local.
dns-sd -L "F TV 桌面" _airplay._tcp local.
```

`/info`：

```bash
python3 - <<'PY'
import socket, plistlib
body=plistlib.dumps({"qualifier":["txtAirPlay"]}, fmt=plistlib.FMT_BINARY)
req=(b"GET /info RTSP/1.0\r\nCSeq: 1\r\nContent-Type: application/x-apple-binary-plist\r\n"
     b"Content-Length: "+str(len(body)).encode()+b"\r\n\r\n"+body)
s=socket.create_connection(("192.168.10.155",5000), timeout=5); s.sendall(req)
data=b""
while True:
    c=s.recv(4096)
    if not c: break
    data+=c
head,_,payload=data.partition(b"\r\n\r\n")
print(head.decode(errors="ignore").splitlines()[0], len(payload), payload[:8])
print(plistlib.loads(payload).keys())
PY
```

真机验证：iPhone 控制中心 → 隔空播放 → 选「F TV 桌面」，同时看
`adb logcat -s FtvCast` 的请求序列（fp-setup → pair-setup → pair-verify → setup → audio）。

## 4. 手机遥控（8089）

```bash
curl -s "http://192.168.10.155:8089/api/ping"
curl -s "http://192.168.10.155:8089/api/status?t=<6位配对码>"
curl -s "http://192.168.10.155:8089/api/key?t=<码>&code=RIGHT"
curl -s "http://192.168.10.155:8089/api/play?t=<码>&url=http%3A%2F%2F192.168.10.113%3A8000%2Ftest.mp4"
```

配对码：`adb shell 'run-as com.jerry.ftvlaunch cat shared_prefs/remote.xml'`

## 5. 界面自查（截图对比）

```bash
adb shell 'am force-stop com.jerry.ftvlaunch; input keyevent KEYCODE_HOME'; sleep 3
adb exec-out screencap -p > /tmp/home.png
```

- 顶栏 7 个入口，`UP` 聚焦顶栏时网格取消高亮
- 抽屉：往下滚到第 6 行仍有高亮；标题不被列表遮挡；整行滚动
- 聚焦放大 4% 不被裁切（抽屉第一行/桌面第一行）
- 返回键在桌面不退出

## 6. 抓包（盒子）

```bash
adb root
adb shell 'tcpdump -i wlan0 -s0 -G 180 -W 1 -w /sdcard/ap.pcap "port 5000 or port 7000 or port 5353"'
adb pull /sdcard/ap.pcap /tmp/ap.pcap
```

历史样本：`/tmp/ap3.pcap`（含 AirReceiver 完整成功流程：fp-setup → ... → 大量 1448B 音频包）。
