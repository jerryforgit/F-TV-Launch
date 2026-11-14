package com.jerry.ftvlaunch;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

/** 手机遥控的局域网服务：HTTP + 内嵌 PWA 页面，按键注入按 root → 无障碍 → 受限 降级。 */
final class ControlServer {

    static final int PORT = 8089;
    private static final String PREFS = "remote";
    private static final String KEY_TOKEN = "token";

    private final Context ctx;
    private ServerSocket server;
    private volatile boolean running;
    private volatile String mode = "unknown"; // root / a11y / limited
    private final Handler main = new Handler(Looper.getMainLooper());
    private CursorOverlay cursor;

    private static ControlServer instance;

    static ControlServer get(Context c) {
        if (instance == null) instance = new ControlServer(c);
        return instance;
    }

    ControlServer(Context c) {
        this.ctx = c.getApplicationContext();
        this.cursor = new CursorOverlay(c);
    }

    static String localIp() {
        try {
            for (java.net.NetworkInterface nic : java.util.Collections.list(
                    java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback()) continue;
                for (java.net.InetAddress a : java.util.Collections.list(nic.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    static String token(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String t = sp.getString(KEY_TOKEN, null);
        if (t == null) {
            t = newToken(c);
        }
        return t;
    }

    static String newToken(Context c) {
        StringBuilder sb = new StringBuilder();
        Random r = new Random();
        for (int i = 0; i < 12; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TOKEN, sb.toString()).apply();
        return sb.toString();
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                server.setReuseAddress(true);
                while (running) {
                    Socket s = server.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (IOException ignored) {
            }
        }, "ftv-remote").start();
        new Thread(this::detectMode, "ftv-remote-mode").start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    String mode() {
        return mode;
    }

    /** root 可用就用 input keyevent，否则看无障碍服务是否开着。 */
    private void detectMode() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            byte[] out = readAll(p.getInputStream());
            p.waitFor();
            if (new String(out, StandardCharsets.UTF_8).contains("uid=0")) {
                mode = "root";
                return;
            }
        } catch (Exception ignored) {
        }
        mode = "a11y";
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String header = readHeader(in);
            if (header.isEmpty()) return;
            String requestLine = header.split("\r\n")[0];
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            respond(s, route(path));
        } catch (Exception ignored) {
        }
    }

    private String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int a = 0, b = 0, c = 0, d;
        while ((d = in.read()) != -1) {
            bos.write(d);
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') break;
            a = b;
            b = c;
            c = d;
            if (bos.size() > 16384) break;
        }
        return new String(bos.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private Response route(String rawPath) {
        String path = rawPath;
        String query = "";
        int q = path.indexOf('?');
        if (q >= 0) {
            query = path.substring(q + 1);
            path = path.substring(0, q);
        }
        if (path.equals("/") || path.equals("/index.html")) {
            return Response.html(PWA);
        }
        if (path.equals("/manifest.json")) {
            return Response.json(MANIFEST, "application/manifest+json");
        }
        if (path.equals("/sw.js")) {
            return Response.js(SW);
        }
        if (path.equals("/icon.png")) {
            return Response.png(icon());
        }
        if (path.equals("/api/ping")) {
            return Response.json("{\"ok\":true,\"mode\":\"" + mode + "\"}");
        }

        String t = param(query, "t");
        if (!token(ctx).equals(t)) {
            return Response.json("{\"ok\":false,\"error\":\"token\"}");
        }
        if (path.equals("/api/status")) {
            return Response.json("{\"ok\":true,\"mode\":\"" + mode + "\",\"a11y\":"
                    + RemoteA11yService.isEnabled() + "}");
        }
        if (path.equals("/api/newtoken")) {
            return Response.json("{\"ok\":true,\"token\":\"" + newToken(ctx) + "\"}");
        }
        if (path.equals("/api/apps")) {
            return Response.json(appsJson());
        }
        if (path.equals("/api/key")) {
            return Response.json(key(param(query, "code")));
        }
        if (path.equals("/api/text")) {
            return Response.json(text(param(query, "text")));
        }
        if (path.equals("/api/media")) {
            return Response.json(media(param(query, "action")));
        }
        if (path.equals("/api/launch")) {
            return Response.json(launch(param(query, "pkg")));
        }
        if (path.equals("/api/cursor")) {
            return Response.json(cursor(param(query, "show")));
        }
        if (path.equals("/api/move")) {
            return Response.json(move(param(query, "dx"), param(query, "dy")));
        }
        if (path.equals("/api/click")) {
            return Response.json(click());
        }
        return Response.json("{\"ok\":false,\"error\":\"not found\"}");
    }

    private String appsJson() {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"apps\":[");
        boolean first = true;
        for (AppRepo.App a : AppRepo.load(ctx)) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"pkg\":\"").append(escape(a.pkg)).append("\",\"label\":\"")
                    .append(escape(a.label)).append("\"}");
        }
        return sb.append("]}").toString();
    }

    // ---------------- 注入 ----------------

    private String key(String code) {
        if (code == null) return err("no code");
        if ("root".equals(mode)) {
            int kc = keyCode(code);
            if (kc <= 0) return err("bad code");
            return runRoot("input keyevent " + kc) ? ok() : err("root failed");
        }
        if ("a11y".equals(mode)) {
            RemoteA11yService svc = RemoteA11yService.get();
            if (svc != null) {
                if ("BACK".equals(code) && svc.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)) return ok();
                if ("HOME".equals(code) && svc.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)) return ok();
                if ("MENU".equals(code) && svc.global(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)) return ok();
            }
            return err("a11y 模式只支持 返回/主页/最近任务");
        }
        return err("没有可用的注入通道");
    }

    private String text(String text) {
        if (text == null || text.isEmpty()) return err("empty");
        if (!"root".equals(mode)) return err("文字输入需要 root");
        String safe = text.replace("\\", "").replace("\"", "").replace("'", "")
                .replace(" ", "%s");
        return runRoot("input text \"" + safe + "\"") ? ok() : err("root failed");
    }

    private String media(String action) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return err("no audio");
        int code;
        if ("play_pause".equals(action)) code = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        else if ("next".equals(action)) code = KeyEvent.KEYCODE_MEDIA_NEXT;
        else if ("prev".equals(action)) code = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        else if ("vol_up".equals(action)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
            return ok();
        } else if ("vol_down".equals(action)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
            return ok();
        } else return err("bad action");

        long now = android.os.SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        return ok();
    }

    private String cursor(String show) {
        if ("1".equals(show)) {
            if (!cursor.canShow()) return err("需要允许「显示在其他应用上层」");
            cursor.show();
        } else {
            cursor.hide();
        }
        return "{\"ok\":true,\"shown\":" + cursor.isShown() + "}";
    }

    private String move(String dx, String dy) {
        try {
            cursor.move(Double.parseDouble(dx), Double.parseDouble(dy));
            return ok();
        } catch (Exception e) {
            return err("bad delta");
        }
    }

    private String click() {
        int[] p = cursor.pos();
        if ("root".equals(mode)) {
            return runRoot("input tap " + p[0] + " " + p[1]) ? ok() : err("root failed");
        }
        RemoteA11yService svc = RemoteA11yService.get();
        if (svc != null && svc.tap(p[0], p[1])) return ok();
        return err("点击需要 root 或无障碍服务");
    }

    private String launch(String pkg) {
        if (pkg == null) return err("no pkg");
        try {
            android.content.Intent i = AppRepo.launchIntent(ctx, pkg);
            if (i == null) return err("cannot launch");
            ctx.startActivity(i);
            return ok();
        } catch (Exception e) {
            return err("launch failed");
        }
    }

    private boolean runRoot(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            p.waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int keyCode(String name) {
        switch (name) {
            case "UP": return KeyEvent.KEYCODE_DPAD_UP;
            case "DOWN": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "LEFT": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "RIGHT": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "OK": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "BACK": return KeyEvent.KEYCODE_BACK;
            case "HOME": return KeyEvent.KEYCODE_HOME;
            case "MENU": return KeyEvent.KEYCODE_MENU;
            default: return -1;
        }
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    private static String err(String m) {
        return "{\"ok\":false,\"error\":\"" + escape(m) + "\"}";
    }

    // ---------------- 工具 ----------------

    static String param(String query, String key) {
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0 && pair.substring(0, i).equals(key)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(i + 1);
                }
            }
        }
        return null;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private byte[] icon() {
        try {
            Drawable d = ctx.getPackageManager().getApplicationIcon(ctx.getPackageName());
            Bitmap bmp = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, 192, 192);
            d.draw(c);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return new byte[0];
        }
    }


    // ---------------- 内嵌 PWA ----------------

    private static final String MANIFEST = "{\"name\":\"F TV 遥控\",\"short_name\":\"F TV 遥控\","
            + "\"start_url\":\"/\",\"display\":\"standalone\",\"background_color\":\"#14161B\","
            + "\"theme_color\":\"#14161B\",\"icons\":[{\"src\":\"/icon.png\",\"sizes\":\"192x192\",\"type\":\"image/png\"}]}";

    private static final String SW = "const C='ftv-remote-v1';"
            + "self.addEventListener('install',e=>{e.waitUntil(caches.open(C).then(c=>c.addAll(['/','/manifest.json'])))});"
            + "self.addEventListener('fetch',e=>{"
            + "if(e.request.method!=='GET'||e.request.url.includes('/api/'))return;"
            + "e.respondWith(caches.match(e.request).then(r=>r||fetch(e.request)))});";

    private static final String PWA = """
<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#14161B">
<link rel="manifest" href="/manifest.json">
<title>F TV 遥控</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
body{margin:0;font-family:-apple-system,system-ui,sans-serif;background:#14161B;color:#e8e8e8;
     padding:14px 14px 24px;touch-action:manipulation;user-select:none}
header{display:flex;align-items:center;justify-content:space-between;margin-bottom:10px}
h1{font-size:17px;margin:0;font-weight:600}
#mode{font-size:12px;color:#9aa0aa;border:1px solid #2b2f38;border-radius:99px;padding:3px 9px}
#pad{position:relative;height:36vh;min-height:220px;border-radius:20px;background:#1b1e24;
     border:1px solid #2b2f38;display:flex;align-items:center;justify-content:center;overflow:hidden}
#pad .hint{color:#5c626d;font-size:13px;text-align:center;line-height:1.6}
#pad.active{background:#20242c}
.grid{display:grid;gap:8px;margin-top:12px}
.keys{grid-template-columns:repeat(3,1fr)}
.keys button{height:52px;font-size:16px}
button{background:#20242c;color:#e8e8e8;border:1px solid #2b2f38;border-radius:14px;
       font-size:15px;padding:10px;transition:.1s}
button:active{background:#2f6bff;border-color:#2f6bff;color:#fff}
.row{display:grid;grid-template-columns:1fr 2fr 1fr;gap:8px;margin-top:8px}
input{width:100%;padding:12px;border-radius:12px;border:1px solid #2b2f38;background:#1b1e24;color:#fff;font-size:16px}
.apps{grid-template-columns:repeat(2,1fr);max-height:34vh;overflow:auto}
.apps button{text-align:left;padding:12px 14px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
h2{font-size:13px;color:#8a909a;margin:16px 0 8px;font-weight:500}
#toast{position:fixed;left:50%;bottom:24px;transform:translateX(-50%);background:#2f6bff;color:#fff;
       padding:8px 16px;border-radius:99px;font-size:13px;opacity:0;transition:.2s;pointer-events:none}
#toast.on{opacity:1}
</style>
</head>
<body>
<header><h1>F TV 遥控</h1>
<div style="display:flex;gap:8px;align-items:center">
<button id="gyro" style="padding:6px 12px;font-size:12px">体感</button>
<button id="cur" style="padding:6px 12px;font-size:12px">光标</button>
<span id="mode">连接中…</span></div></header>

<div id="pad"><div class="hint">触控板<br>滑动 = 方向 · 点按 = OK<br>双击 = 返回 · 长按 = 菜单</div></div>

<div class="grid keys">
  <button data-k="MENU">菜单</button>
  <button data-k="UP">▲</button>
  <button data-k="HOME">主页</button>
  <button data-k="LEFT">◀</button>
  <button data-k="OK">OK</button>
  <button data-k="RIGHT">▶</button>
  <button data-k="BACK">返回</button>
  <button data-k="DOWN">▼</button>
  <button data-m="play_pause">播放/暂停</button>
</div>

<div class="row">
  <button data-m="vol_down">音量 −</button>
  <button data-m="play_pause">播放 / 暂停</button>
  <button data-m="vol_up">音量 +</button>
</div>

<h2>键盘输入</h2>
<div class="row" style="grid-template-columns:1fr auto">
  <input id="txt" placeholder="输入文字后发送到电视" autocomplete="off">
  <button id="send">发送</button>
</div>

<h2>应用</h2>
<div class="grid apps" id="apps"></div>

<div id="toast"></div>

<script>
const qs=new URLSearchParams(location.search);
let token=qs.get('t')||localStorage.getItem('ftv_token');
if(qs.get('t'))localStorage.setItem('ftv_token',qs.get('t'));
const $=id=>document.getElementById(id);
function toast(m){const el=$('toast');el.textContent=m;el.classList.add('on');setTimeout(()=>el.classList.remove('on'),1200)}
async function api(p,extra){
  if(!token){toast('请重新扫码配对');return null}
  const u='/api/'+p+'?t='+encodeURIComponent(token)+(extra?'&'+extra:'');
  try{const r=await fetch(u);return await r.json()}catch(e){toast('电视没有响应');return null}
}
async function status(){
  const s=await api('ping');
  if(s&&s.ok){$('mode').textContent=s.mode==='root'?'root 模式':(s.mode==='a11y'?'无障碍模式':'受限模式')}
  else{$('mode').textContent=token?'未连接':'未配对'}
}
status();setInterval(status,15000);

function key(k){api('key','code='+k)}
document.querySelectorAll('[data-k]').forEach(b=>b.addEventListener('click',()=>key(b.dataset.k)));
document.querySelectorAll('[data-m]').forEach(b=>b.addEventListener('click',()=>api('media','action='+b.dataset.m)));

// 触控板
const pad=$('pad');
let sx=0,sy=0,lx=0,ly=0,moved=false,lastT=0,timer=null,lastTap=0;
function dk(k){key(k)}
pad.addEventListener('touchstart',e=>{
  const t=e.touches[0];sx=lx=t.clientX;sy=ly=t.clientY;moved=false;pad.classList.add('active');
  clearTimeout(timer);
  timer=setTimeout(()=>{if(!moved){dk('MENU');navigator.vibrate&&navigator.vibrate(20)}},650);
},{passive:true});
pad.addEventListener('touchmove',e=>{
  const t=e.touches[0],dx=t.clientX-lx,dy=t.clientY-ly;
  if(Math.abs(t.clientX-sx)>8||Math.abs(t.clientY-sy)>8)moved=true;
  const step=46;
  if(Math.abs(dx)>Math.abs(dy)){
    if(dx>step){dk('RIGHT');lx=t.clientX}
    else if(dx<-step){dk('LEFT');lx=t.clientX}
  }else{
    if(dy>step){dk('DOWN');ly=t.clientY}
    else if(dy<-step){dk('UP');ly=t.clientY}
  }
},{passive:true});
pad.addEventListener('touchend',e=>{
  clearTimeout(timer);pad.classList.remove('active');
  if(gyro){api('click');return}
  if(moved)return;
  const now=Date.now();
  if(now-lastTap<280){dk('BACK');lastTap=0;return}
  lastTap=now;dk('OK');
},{passive:true});

$('send').addEventListener('click',async()=>{
  const v=$('txt').value.trim();if(!v)return;
  const r=await api('text','text='+encodeURIComponent(v));
  if(r&&r.ok){$('txt').value='';toast('已发送')}else toast(r&&r.error?r.error:'发送失败');
});
$('txt').addEventListener('keydown',e=>{if(e.key==='Enter')$('send').click()});

async function apps(){
  const r=await api('apps');if(!r||!r.apps)return;
  const box=$('apps');box.innerHTML='';
  r.apps.forEach(a=>{
    const b=document.createElement('button');
    b.textContent=a.label;
    b.onclick=async()=>{const x=await api('launch','pkg='+encodeURIComponent(a.pkg));if(x&&x.ok)toast('已启动 '+a.label)};
    box.appendChild(b);
  });
}
apps();

// ---- 体感（安卓测试可用；iOS 需要 HTTPS 才能拿陀螺仪）----
let gyro=false,gx=null,gy=null,lastSend=0,cursorOn=false;
$('gyro').addEventListener('click',toggleGyro);
$('cur').addEventListener('click',async()=>{
  cursorOn=!cursorOn;
  const r=await api('cursor','show='+(cursorOn?1:0));
  if(r&&!r.ok){toast(r.error||'无法显示光标');cursorOn=false;return}
  $('cur').style.background=cursorOn?'#2f6bff':'';
});
async function toggleGyro(){
  if(gyro){
    gyro=false;window.removeEventListener('deviceorientation',onOrient,true);
    $('gyro').style.background='';
    pad.querySelector('.hint').textContent='触控板\n滑动 = 方向 · 点按 = OK\n双击 = 返回 · 长按 = 菜单';
    return;
  }
  if(typeof DeviceOrientationEvent!=='undefined'&&DeviceOrientationEvent.requestPermission){
    try{const p=await DeviceOrientationEvent.requestPermission();if(p!=='granted'){toast('没有陀螺仪权限');return}}
    catch(e){toast('iOS 需要 HTTPS 才能用体感');return}
  }
  gyro=true;gx=gy=null;
  window.addEventListener('deviceorientation',onOrient,true);
  $('gyro').style.background='#2f6bff';
  pad.querySelector('.hint').textContent='体感模式\n倾斜手机移动光标 · 点按 = 点击';
  if(!cursorOn){cursorOn=true;api('cursor','show=1');$('cur').style.background='#2f6bff'}
}
function onOrient(e){
  if(!gyro||e.gamma==null)return;
  const nx=e.gamma,ny=e.beta;
  if(gx===null){gx=nx;gy=ny;return}
  const dx=nx-gx,dy=ny-gy;gx=nx;gy=ny;
  const now=Date.now();if(now-lastSend<35)return;lastSend=now;
  if(Math.abs(dx)<0.03&&Math.abs(dy)<0.03)return;
  const k=3.2;
  api('move','dx='+(dx*k).toFixed(2)+'&dy='+(dy*k).toFixed(2));
}
</script>
</body>
</html>
""";

    private static final class Response {
        final String body;
        final String type;
        final byte[] bytes;

        private Response(String body, String type, byte[] bytes) {
            this.body = body;
            this.type = type;
            this.bytes = bytes;
        }

        static Response html(String b) {
            return new Response(b, "text/html; charset=utf-8", null);
        }

        static Response json(String b) {
            return new Response(b, "application/json; charset=utf-8", null);
        }

        static Response json(String b, String type) {
            return new Response(b, type + "; charset=utf-8", null);
        }

        static Response js(String b) {
            return new Response(b, "application/javascript; charset=utf-8", null);
        }

        static Response png(byte[] b) {
            return new Response(null, "image/png", b);
        }
    }

    private static void respond(Socket s, Response r) throws IOException {
        byte[] bytes = r.bytes != null ? r.bytes : r.body.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + r.type + "\r\n"
                + "Cache-Control: no-store\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }
}
