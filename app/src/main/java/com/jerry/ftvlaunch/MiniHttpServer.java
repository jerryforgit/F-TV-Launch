package com.jerry.ftvlaunch;

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

/** 极简 HTTP 服务：给手机一个上传页面，接收 multipart 文件。 */
final class MiniHttpServer {

    interface Listener {
        void onFile(String name, File file);
    }

    interface Actions {
        String filesJson();

        boolean setWallpaper(String name);

        boolean install(String name);
    }

    private final int port;
    private final File dir;
    private final Listener listener;
    private Actions actions;
    private ServerSocket server;
    private volatile boolean running;

    MiniHttpServer(int port, File dir, Listener listener, Actions actions) {
        this.port = port;
        this.dir = dir;
        this.listener = listener;
        this.actions = actions;
    }

    void start() {
        running = true;
        new Thread(() -> {
            try {
                server = new ServerSocket(port);
                server.setReuseAddress(true);
                while (running) {
                    Socket s = server.accept();
                    new Thread(() -> handle(s)).start();
                }
            } catch (IOException ignored) {
            }
        }, "ftv2-http").start();
    }

    void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int prev3 = 0, prev2 = 0, prev1 = 0, b;
            while ((b = in.read()) != -1) {
                head.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
                prev3 = prev2;
                prev2 = prev1;
                prev1 = b;
            }
            String header = new String(head.toByteArray(), StandardCharsets.ISO_8859_1);
            String[] lines = header.split("\r\n");
            if (lines.length == 0) return;
            String requestLine = lines[0];
            boolean post = requestLine.startsWith("POST");
            int length = 0;
            String boundary = null;
            for (String line : lines) {
                String lower = line.toLowerCase(Locale.US);
                if (lower.startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring(15).trim());
                } else if (lower.startsWith("content-type:") && lower.contains("boundary=")) {
                    boundary = line.substring(line.indexOf("boundary=") + 9).trim();
                }
            }

            String path = requestLine.split(" ").length > 1 ? requestLine.split(" ")[1] : "/";
            if (path.startsWith("/files")) {
                respondJson(s, actions == null ? "{\"files\":[]}" : actions.filesJson());
                return;
            }
            if (path.startsWith("/action") && actions != null) {
                String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
                String doWhat = param(query, "do");
                String name = param(query, "name");
                boolean ok = "wallpaper".equals(doWhat) ? actions.setWallpaper(name)
                        : "install".equals(doWhat) && actions.install(name);
                respondJson(s, "{\"ok\":" + ok + "}");
                return;
            }
            boolean wantsJson = header.toLowerCase(Locale.US).contains("application/json");
            if (post && boundary != null && length > 0) {
                byte[] body = readFully(in, length);
                saveParts(body, boundary);
                if (wantsJson) {
                    respondJson(s, "{\"ok\":true}");
                } else {
                    respond(s, page("上传成功", "<h2>上传完成</h2><p>可以继续上传，或回到电视查看。</p>"));
                }
            } else {
                respond(s, page("传文件到电视", uploadForm()));
            }
        } catch (Exception ignored) {
        }
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(data, off, length - off);
            if (n < 0) break;
            off += n;
        }
        return data;
    }

    private void saveParts(byte[] body, String boundary) {
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        int start = indexOf(body, delimiter, 0);
        while (start >= 0) {
            int partStart = start + delimiter.length;
            if (partStart + 1 < body.length && body[partStart] == '-' && body[partStart + 1] == '-') break;
            if (partStart + 1 < body.length && body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            }
            int next = indexOf(body, delimiter, partStart);
            if (next < 0) break;
            int headerEnd = indexOf(body, "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1), partStart);
            if (headerEnd > 0 && headerEnd < next) {
                String headers = new String(body, partStart, headerEnd - partStart, StandardCharsets.ISO_8859_1);
                String name = extractName(headers);
                if (name != null) {
                    int dataStart = headerEnd + 4;
                    int dataEnd = next;
                    if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') dataEnd -= 2;
                    if (dataEnd > dataStart) {
                        File out = new File(dir, sanitize(name));
                        try (FileOutputStream fos = new FileOutputStream(out)) {
                            fos.write(body, dataStart, dataEnd - dataStart);
                        } catch (IOException ignored) {
                        }
                        if (listener != null) listener.onFile(out.getName(), out);
                    }
                }
            }
            start = next;
        }
    }

    private static String extractName(String headers) {
        for (String line : headers.split("\r\n")) {
            String lower = line.toLowerCase(Locale.US);
            int idx = lower.indexOf("filename=\"");
            if (idx >= 0) {
                int end = line.indexOf('"', idx + 10);
                if (end > idx) {
                    String n = line.substring(idx + 10, end);
                    if (!n.isEmpty()) {
                        // 浏览器上传的文件名通常是 UTF-8，但 multipart 头按 ISO-8859-1 读，转回来
                        try {
                            String fixed = new String(n.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
                            if (!fixed.contains("\uFFFD")) n = fixed;
                        } catch (RuntimeException ignored) {
                        }
                        return n;
                    }
                }
            }
        }
        return null;
    }

    private static String sanitize(String name) {
        String n = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return n.length() > 120 ? n.substring(n.length() - 120) : n;
    }

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

    private static int indexOf(byte[] data, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= data.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static void respond(Socket s, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static void respondJson(Socket s, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static final String CSS = "body{font-family:-apple-system,system-ui,sans-serif;margin:0;padding:20px;background:#f4f6fa;color:#1a1f2b}"
            + "h2{margin:0 0 6px;font-size:22px}p.sub{margin:0 0 18px;color:#6b7280;font-size:14px;line-height:1.5}"
            + "#drop{display:flex;flex-direction:column;align-items:center;justify-content:center;min-height:170px;"
            + "border:2px dashed #b9c4d4;border-radius:18px;background:#fff;transition:.15s;text-align:center;padding:20px}"
            + "#drop.over{border-color:#2f6bff;background:#eef3ff}"
            + "#drop .plus{font-size:44px;color:#9aa7bb;line-height:1}"
            + "#drop .hint{margin-top:8px;color:#6b7280;font-size:14px}"
            + "input[type=file]{display:none}"
            + "#list{margin-top:18px}"
            + ".item{background:#fff;border-radius:14px;padding:12px 14px;margin-bottom:10px;box-shadow:0 1px 2px rgba(16,24,40,.06)}"
            + ".row{display:flex;justify-content:space-between;gap:12px;font-size:14px}"
            + ".name{font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"
            + ".size{color:#6b7280;flex:none}"
            + ".bar{height:6px;border-radius:99px;background:#e8edf5;margin-top:10px;overflow:hidden}"
            + ".bar i{display:block;height:100%;width:0;background:#2f6bff;border-radius:99px;transition:width .15s}"
            + ".status{margin-top:6px;font-size:12px;color:#6b7280}"
            + ".ok .bar i{background:#16a34a}.ok .status{color:#16a34a}"
            + ".err .bar i{background:#dc2626}.err .status{color:#dc2626}"
            + "h2.sec{margin:26px 0 10px;font-size:17px}"
            + ".act{margin-top:10px;width:100%;padding:11px;font-size:15px;border:0;border-radius:11px;background:#2f6bff;color:#fff}"
            + ".act[disabled]{background:#9db4e8}"
            + ".empty{color:#9aa7bb;font-size:14px;padding:10px 4px}";

    private static String page(String title, String body) {
        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>" + title + "</title><style>" + CSS + "</style></head><body>"
                + body + "</body></html>";
    }

    private static String uploadForm() {
        String js = """
                var drop=document.getElementById('drop'),input=document.getElementById('file'),list=document.getElementById('list'),filesBox=document.getElementById('files');
                ['dragenter','dragover','dragleave','drop'].forEach(function(t){document.addEventListener(t,function(e){e.preventDefault()},false)});
                document.addEventListener('dragover',function(e){e.dataTransfer.dropEffect='copy'},false);
                function add(files){for(var i=0;i<files.length;i++){upload(files[i])}}
                document.addEventListener('drop',function(e){
                  e.preventDefault();drop.classList.remove('over');
                  if(e.dataTransfer&&e.dataTransfer.files&&e.dataTransfer.files.length){add(e.dataTransfer.files)}
                },false);
                drop.addEventListener('click',function(){input.click()});
                input.addEventListener('change',function(){add(input.files);input.value=''});
                ['dragenter','dragover'].forEach(function(t){drop.addEventListener(t,function(e){e.preventDefault();drop.classList.add('over')})});
                drop.addEventListener('dragleave',function(e){e.preventDefault();drop.classList.remove('over')});
                function kb(n){if(n<1024)return n+' B';if(n<1048576)return (n/1024).toFixed(1)+' KB';return (n/1048576).toFixed(1)+' MB'}
                function el(tag,cls,text){var e=document.createElement(tag);if(cls)e.className=cls;if(text)e.textContent=text;return e}
                function upload(f){
                  var name=el('div','name',f.name);
                  var size=el('div','size',kb(f.size));
                  var row=el('div','row');row.appendChild(name);row.appendChild(size);
                  var bar=document.createElement('i');
                  var barWrap=el('div','bar');barWrap.appendChild(bar);
                  var st=el('div','status','等待上传…');
                  var item=el('div','item');item.appendChild(row);item.appendChild(barWrap);item.appendChild(st);
                  list.insertBefore(item,list.firstChild);
                  var xhr=new XMLHttpRequest();
                  xhr.open('POST','/upload');
                  xhr.setRequestHeader('Accept','application/json');
                  xhr.upload.onprogress=function(e){
                    if(e.lengthComputable){var p=Math.round(e.loaded*100/e.total);bar.style.width=p+'%';st.textContent='上传中 '+p+'%'}
                  };
                  xhr.onload=function(){
                    if(xhr.status===200){item.classList.add('ok');bar.style.width='100%';st.textContent='已上传';refreshFiles()}
                    else{item.classList.add('err');st.textContent='失败 '+xhr.status}
                  };
                  xhr.onerror=function(){item.classList.add('err');st.textContent='网络错误'};
                  var fd=new FormData();fd.append('file',f,f.name);st.textContent='开始上传…';xhr.send(fd);
                }
                function refreshFiles(){
                  fetch('/files').then(function(r){return r.json()}).then(function(d){
                    filesBox.innerHTML='';
                    if(!d.files||!d.files.length){filesBox.appendChild(el('div','empty','还没有文件，先上传一个吧'));return}
                    d.files.forEach(function(f){
                      var item=el('div','item');
                      var row=el('div','row');
                      row.appendChild(el('div','name',f.name));
                      row.appendChild(el('div','size',kb(f.size)));
                      item.appendChild(row);
                      if(f.kind==='image'||f.kind==='apk'){
                        var btn=el('button','act',f.kind==='image'?'设为背景':'安装');
                        btn.onclick=function(){
                          var act=f.kind==='image'?'wallpaper':'install';
                          btn.disabled=true;btn.textContent='处理中…';
                          fetch('/action?do='+act+'&name='+encodeURIComponent(f.name),{method:'POST'})
                            .then(function(r){return r.json()})
                            .then(function(x){btn.textContent=x.ok?(f.kind==='image'?'已设为背景':'已发送安装'):'失败';setTimeout(refreshFiles,1500)})
                            .catch(function(){btn.textContent='失败';btn.disabled=false});
                        };
                        item.appendChild(btn);
                      }
                      filesBox.appendChild(item);
                    });
                  }).catch(function(){});
                }
                refreshFiles();
                """;
        return "<h2>传文件到电视</h2>"
                + "<p class='sub'>拖拽文件到这里，或点一下选择。可以一次选多个，传完还能继续加。</p>"
                + "<div id='drop'><div class='plus'>＋</div><div class='hint'>拖拽 / 点击选择文件</div></div>"
                + "<input id='file' type='file' multiple>"
                + "<div id='list'></div>"
                + "<h2 class='sec'>电视上的文件</h2>"
                + "<div id='files'></div>"
                + "<script>" + js + "</script>";
    }
}
