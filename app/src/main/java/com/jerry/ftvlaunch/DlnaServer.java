package com.jerry.ftvlaunch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * 极简 DLNA / UPnP AV 接收端：
 * SSDP 广播（手机能在"投屏"里看到）+ HTTP 设备描述 + SOAP 控制。
 * 播放交给 {@link DlnaPlayerActivity}。
 */
final class DlnaServer {

    static final int PORT = 8090;
    private static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CMS = "urn:schemas-upnp-org:service:ConnectionManager:1";

    private static DlnaServer instance;

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private String uuid;

    // 播放状态：由 DlnaPlayerActivity 维护
    static volatile String transportState = "STOPPED";
    static volatile String currentUri = "";
    static volatile String currentMeta = "";
    static volatile long durationMs;
    static volatile long positionMs;
    static volatile int volume = 50;
    static volatile boolean muted;
    static volatile DlnaPlayerActivity player;

    static synchronized DlnaServer get(Context c) {
        if (instance == null) instance = new DlnaServer(c.getApplicationContext());
        return instance;
    }

    private DlnaServer(Context c) {
        ctx = c;
        SharedPreferences sp = c.getSharedPreferences("dlna", Context.MODE_PRIVATE);
        uuid = sp.getString("uuid", null);
        if (uuid == null) {
            uuid = "ftv-" + Long.toHexString(new Random().nextLong()).replace("-", "");
            sp.edit().putString("uuid", uuid).apply();
        }
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(this::httpLoop, "dlna-http").start();
        new Thread(this::ssdpLoop, "dlna-ssdp").start();
        new Thread(this::notifyAlive, "dlna-notify").start();
    }

    // ---------------- SSDP ----------------

    private void ssdpLoop() {
        try {
            MulticastSocket sock = new MulticastSocket(1900);
            sock.setReuseAddress(true);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            sock.joinGroup(new InetSocketAddress(group, 1900), NetworkInterface.getByInetAddress(
                    InetAddress.getByName(localIp())));
            byte[] buf = new byte[4096];
            DatagramPacket packet = new DatagramPacket(buf, buf.length);
            while (running) {
                packet.setLength(buf.length);
                sock.receive(packet);
                String msg = new String(packet.getData(), 0, packet.getLength(),
                        StandardCharsets.UTF_8);
                if (!msg.startsWith("M-SEARCH")) continue;
                String st = header(msg, "ST");
                if (st == null) continue;
                if (!st.equals("ssdp:all") && !st.equals("upnp:rootdevice")
                        && !st.equals(DEVICE_TYPE) && !st.contains(uuid)) continue;
                String reply = "HTTP/1.1 200 OK\r\n"
                        + "CACHE-CONTROL: max-age=1800\r\n"
                        + "DATE: " + httpDate() + "\r\n"
                        + "EXT:\r\n"
                        + "LOCATION: " + baseUrl() + "/desc.xml\r\n"
                        + "SERVER: Linux/3.0 UPnP/1.0 FTVLauncher/1.0\r\n"
                        + "ST: " + st + "\r\n"
                        + "USN: uuid:" + uuid + (st.equals("upnp:rootdevice")
                        ? "::upnp:rootdevice" : st.equals(DEVICE_TYPE) ? "::" + DEVICE_TYPE : "")
                        + "\r\n\r\n";
                byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                sock.send(new DatagramPacket(out, out.length, packet.getAddress(), packet.getPort()));
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyAlive() {
        try {
            InetAddress group = InetAddress.getByName("239.255.255.250");
            MulticastSocket sock = new MulticastSocket();
            String[] entries = {"upnp:rootdevice", DEVICE_TYPE};
            for (int round = 0; round < 3 && running; round++) {
                for (String st : entries) {
                    String msg = "NOTIFY * HTTP/1.1\r\n"
                            + "HOST: 239.255.255.250:1900\r\n"
                            + "CACHE-CONTROL: max-age=1800\r\n"
                            + "LOCATION: " + baseUrl() + "/desc.xml\r\n"
                            + "NT: " + st + "\r\n"
                            + "NTS: ssdp:alive\r\n"
                            + "SERVER: Linux/3.0 UPnP/1.0 FTVLauncher/1.0\r\n"
                            + "USN: uuid:" + uuid + "::" + st + "\r\n\r\n";
                    byte[] out = msg.getBytes(StandardCharsets.UTF_8);
                    sock.send(new DatagramPacket(out, out.length, group, 1900));
                }
                Thread.sleep(800);
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------- HTTP ----------------

    private void httpLoop() {
        try {
            ServerSocket server = new ServerSocket(PORT);
            server.setReuseAddress(true);
            while (running) {
                Socket s = server.accept();
                new Thread(() -> handle(s)).start();
            }
        } catch (IOException ignored) {
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String header = readHeader(in);
            if (header.isEmpty()) return;
            String[] lines = header.split("\r\n");
            String[] first = lines[0].split(" ");
            if (first.length < 2) return;
            String method = first[0];
            String path = first[1];
            int len = 0;
            String soapAction = "";
            for (String line : lines) {
                String low = line.toLowerCase(Locale.US);
                if (low.startsWith("content-length:")) {
                    len = Integer.parseInt(line.substring(15).trim());
                } else if (low.startsWith("soapaction:")) {
                    soapAction = line.substring(11).trim().replace("\"", "");
                }
            }
            if (path.equals("/desc.xml")) {
                respond(s, 200, "text/xml; charset=utf-8", desc());
                return;
            }
            if (path.startsWith("/scpd/")) {
                String name = path.substring(6).replace(".xml", "");
                respond(s, 200, "text/xml; charset=utf-8", scpd(name));
                return;
            }
            if (method.equals("SUBSCRIBE")) {
                respond(s, 200, "text/xml; charset=utf-8", "", "SID: uuid:" + uuid + "-evt\r\n"
                        + "TIMEOUT: Second-1800\r\n");
                return;
            }
            if (path.startsWith("/control/")) {
                String body = len > 0 ? new String(readFully(in, len), StandardCharsets.UTF_8) : "";
                respond(s, 200, "text/xml; charset=utf-8", soap(soapAction, body));
                return;
            }
            respond(s, 404, "text/plain", "not found");
        } catch (Exception ignored) {
        }
    }

    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int a = 0, b = 0, c = 0, d;
        while ((d = in.read()) != -1) {
            out.write(d);
            if (a == '\r' && b == '\n' && c == '\r' && d == '\n') break;
            a = b;
            b = c;
            c = d;
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(data, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return data;
    }

    private void respond(Socket s, int code, String type, String body, String... extra) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(code).append(" OK\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(bytes.length).append("\r\n");
        head.append("Connection: close\r\n");
        for (String e : extra) if (e != null) head.append(e);
        head.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    // ---------------- 描述 ----------------

    private String baseUrl() {
        return "http://" + localIp() + ":" + PORT;
    }

    private String localIp() {
        try {
            java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                java.util.Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof java.net.Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private String desc() {
        return "<?xml version=\"1.0\"?>"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\""
                + " xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType>"
                + "<friendlyName>F TV 桌面</friendlyName>"
                + "<manufacturer>F TV Launcher</manufacturer>"
                + "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>"
                + "<modelName>F TV Launcher</modelName>"
                + "<modelNumber>1</modelNumber>"
                + "<UDN>uuid:" + uuid + "</UDN>"
                + "<serviceList>"
                + service(AVT, "urn:upnp-org:serviceId:AVTransport", "avt")
                + service(RCS, "urn:upnp-org:serviceId:RenderingControl", "rcs")
                + service(CMS, "urn:upnp-org:serviceId:ConnectionManager", "cms")
                + "</serviceList>"
                + "</device></root>";
    }

    private String service(String type, String id, String path) {
        return "<service><serviceType>" + type + "</serviceType>"
                + "<serviceId>" + id + "</serviceId>"
                + "<controlURL>/control/" + path + "</controlURL>"
                + "<eventSubURL>/event/" + path + "</eventSubURL>"
                + "<SCPDURL>/scpd/" + path + ".xml</SCPDURL></service>";
    }

    private String scpd(String name) {
        String service;
        String actions;
        if ("avt".equals(name)) {
            service = AVT;
            actions = "<action><name>SetAVTransportURI</name><argumentList>"
                    + arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")
                    + arg("CurrentURI", "in", "AVTransportURI")
                    + arg("CurrentURIMetaData", "in", "AVTransportURIMetaData")
                    + "</argumentList></action>"
                    + action("Play") + action("Pause") + action("Stop")
                    + "<action><name>Seek</name><argumentList>"
                    + arg("InstanceID", "in", "A_ARG_TYPE_InstanceID")
                    + arg("Unit", "in", "A_ARG_TYPE_SeekMode")
                    + arg("Target", "in", "A_ARG_TYPE_SeekTarget")
                    + "</argumentList></action>"
                    + action("GetTransportInfo") + action("GetPositionInfo")
                    + action("GetMediaInfo") + action("GetDeviceCapabilities")
                    + action("GetTransportSettings");
        } else if ("rcs".equals(name)) {
            service = RCS;
            actions = "<action><name>GetVolume</name></action>"
                    + "<action><name>SetVolume</name></action>"
                    + "<action><name>GetMute</name></action>"
                    + "<action><name>SetMute</name></action>";
        } else {
            service = CMS;
            actions = "<action><name>GetProtocolInfo</name></action>"
                    + "<action><name>GetCurrentConnectionIDs</name></action>"
                    + "<action><name>GetCurrentConnectionInfo</name></action>";
        }
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion>"
                + "<actionList>" + actions + "</actionList>"
                + "<serviceStateTable><stateVariable sendEvents=\"no\">"
                + "<name>LastChange</name><dataType>string</dataType>"
                + "</stateVariable></serviceStateTable></scpd>";
    }

    private String action(String name) {
        return "<action><name>" + name + "</name></action>";
    }

    private String arg(String name, String dir, String rel) {
        return "<argument><name>" + name + "</name><direction>" + dir
                + "</direction><relatedStateVariable>" + rel
                + "</relatedStateVariable></argument>";
    }

    // ---------------- SOAP ----------------

    private String soap(String soapAction, String body) {
        String service = soapAction.contains("#") ? soapAction.substring(0, soapAction.indexOf('#')) : "";
        String action = soapAction.contains("#") ? soapAction.substring(soapAction.indexOf('#') + 1) : "";
        try {
            if (AVT.equals(service)) return soapAvt(action, body);
            if (RCS.equals(service)) return soapRcs(action, body);
            if (CMS.equals(service)) return soapCms(action);
        } catch (Exception ignored) {
        }
        return soapError(service, action);
    }

    private String envelope(String service, String action, String inner) {
        return "<?xml version=\"1.0\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + "Response xmlns:u=\"" + service + "\">"
                + inner
                + "</u:" + action + "Response></s:Body></s:Envelope>";
    }

    private String soapError(String service, String action) {
        return "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>"
                + "<detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                + "<errorCode>401</errorCode><errorDescription>Invalid Action</errorDescription>"
                + "</UPnPError></detail></s:Fault></s:Body></s:Envelope>";
    }

    private String soapAvt(String action, String body) {
        switch (action) {
            case "SetAVTransportURI": {
                currentUri = unesc(xmlArg(body, "CurrentURI"));
                currentMeta = xmlArg(body, "CurrentURIMetaData").replace("&lt;", "<")
                        .replace("&gt;", ">").replace("&amp;", "&");
                durationMs = 0;
                positionMs = 0;
                transportState = "STOPPED";
                openPlayer(currentUri, metaTitle(currentMeta));
                return envelope(AVT, action, "");
            }
            case "Play": {
                callPlayer(p -> p.doPlay());
                return envelope(AVT, action, "");
            }
            case "Pause": {
                callPlayer(p -> p.doPause());
                return envelope(AVT, action, "");
            }
            case "Stop": {
                callPlayer(p -> p.doStop());
                return envelope(AVT, action, "");
            }
            case "Seek": {
                String target = xmlArg(body, "Target");
                long ms = parseTime(target);
                callPlayer(p -> p.doSeek(ms));
                return envelope(AVT, action, "");
            }
            case "GetTransportInfo":
                return envelope(AVT, action,
                        "<CurrentTransportState>" + transportState + "</CurrentTransportState>"
                                + "<CurrentTransportStatus>OK</CurrentTransportStatus>"
                                + "<CurrentSpeed>1</CurrentSpeed>");
            case "GetPositionInfo":
                return envelope(AVT, action,
                        "<Track>1</Track>"
                                + "<TrackDuration>" + time(durationMs) + "</TrackDuration>"
                                + "<TrackMetaData>" + esc(currentMeta) + "</TrackMetaData>"
                                + "<TrackURI>" + esc(currentUri) + "</TrackURI>"
                                + "<RelTime>" + time(positionMs) + "</RelTime>"
                                + "<AbsTime>" + time(positionMs) + "</AbsTime>"
                                + "<RelCount>0</RelCount>"
                                + "<AbsCount>2147483647</AbsCount>");
            case "GetMediaInfo":
                return envelope(AVT, action,
                        "<NrTracks>1</NrTracks>"
                                + "<MediaDuration>" + time(durationMs) + "</MediaDuration>"
                                + "<CurrentURI>" + esc(currentUri) + "</CurrentURI>"
                                + "<CurrentURIMetaData>" + esc(currentMeta) + "</CurrentURIMetaData>"
                                + "<NextURI></NextURI><NextURIMetaData></NextURIMetaData>"
                                + "<PlayMedium>NETWORK</PlayMedium>"
                                + "<RecordMedium>NOT_IMPLEMENTED</RecordMedium>"
                                + "<WriteStatus>NOT_IMPLEMENTED</WriteStatus>");
            case "GetDeviceCapabilities":
                return envelope(AVT, action,
                        "<PlayMedia>NETWORK</PlayMedia><RecMedia>NOT_IMPLEMENTED</RecMedia>"
                                + "<RecQualityModes>NOT_IMPLEMENTED</RecQualityModes>");
            case "GetTransportSettings":
                return envelope(AVT, action,
                        "<PlayMode>NORMAL</PlayMode><RecQualityMode>NOT_IMPLEMENTED</RecQualityMode>");
            default:
                return soapError(AVT, action);
        }
    }

    private String soapRcs(String action, String body) {
        switch (action) {
            case "GetVolume":
                return envelope(RCS, action, "<CurrentVolume>" + volume + "</CurrentVolume>");
            case "SetVolume": {
                try {
                    volume = Math.max(0, Math.min(100,
                            Integer.parseInt(xmlArg(body, "DesiredVolume"))));
                    setSystemVolume(volume);
                } catch (Exception ignored) {
                }
                return envelope(RCS, action, "");
            }
            case "GetMute":
                return envelope(RCS, action, "<CurrentMute>" + (muted ? 1 : 0) + "</CurrentMute>");
            case "SetMute": {
                muted = "1".equals(xmlArg(body, "DesiredMute"));
                setSystemMute(muted);
                return envelope(RCS, action, "");
            }
            default:
                return soapError(RCS, action);
        }
    }

    private String soapCms(String action) {
        switch (action) {
            case "GetProtocolInfo":
                return envelope(CMS, action,
                        "<Source></Source><Sink>"
                                + "http-get:*:video/mp4:*,"
                                + "http-get:*:video/x-matroska:*,"
                                + "http-get:*:video/mpeg:*,"
                                + "http-get:*:video/quicktime:*,"
                                + "http-get:*:audio/mpeg:*,"
                                + "http-get:*:audio/mp4:*,"
                                + "http-get:*:audio/aac:*,"
                                + "http-get:*:image/jpeg:*,"
                                + "http-get:*:image/png:*"
                                + "</Sink>");
            case "GetCurrentConnectionIDs":
                return envelope(CMS, action, "<ConnectionIDs>0</ConnectionIDs>");
            case "GetCurrentConnectionInfo":
                return envelope(CMS, action,
                        "<RcsID>0</RcsID><AVTransportID>0</AVTransportID>"
                                + "<ProtocolInfo></ProtocolInfo><PeerConnectionManager></PeerConnectionManager>"
                                + "<PeerConnectionID>-1</PeerConnectionID>"
                                + "<Direction>Input</Direction><Status>OK</Status>");
            default:
                return soapError(CMS, action);
        }
    }

    private interface PlayerCall {
        void run(DlnaPlayerActivity p);
    }

    private void callPlayer(PlayerCall call) {
        main.post(() -> {
            DlnaPlayerActivity p = player;
            if (p != null) call.run(p);
        });
    }

    private void openPlayer(String uri, String title) {
        if (uri == null || uri.isEmpty()) return;
        main.post(() -> {
            Intent i = new Intent(ctx, DlnaPlayerActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            i.putExtra(DlnaPlayerActivity.EXTRA_URI, uri);
            i.putExtra(DlnaPlayerActivity.EXTRA_TITLE, title);
            ctx.startActivity(i);
        });
    }

    private void setSystemVolume(int v) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * v / 100f), 0);
        } catch (Exception ignored) {
        }
    }

    private void setSystemMute(boolean m) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    m ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, 0);
        } catch (Exception ignored) {
        }
    }

    // ---------------- 工具 ----------------

    private static String header(String msg, String name) {
        for (String line : msg.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase(name)) {
                return line.substring(c + 1).trim();
            }
        }
        return null;
    }

    private static String xmlArg(String body, String name) {
        if (body == null) return "";
        int start = body.indexOf("<" + name + ">");
        if (start < 0) return "";
        start += name.length() + 2;
        int end = body.indexOf("</" + name + ">", start);
        if (end < 0) return "";
        return body.substring(start, end).trim();
    }

    private static String metaTitle(String meta) {
        if (meta == null) return "";
        int s = meta.indexOf("<dc:title>");
        if (s < 0) return "";
        s += 10;
        int e = meta.indexOf("</dc:title>", s);
        return e > s ? meta.substring(s, e) : "";
    }

    static String time(long ms) {
        long total = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    static long parseTime(String t) {
        try {
            String[] p = t.split(":");
            if (p.length == 3) {
                return (Long.parseLong(p[0]) * 3600 + Long.parseLong(p[1]) * 60
                        + Math.round(Double.parseDouble(p[2]))) * 1000;
            }
            if (p.length == 1) return Math.round(Double.parseDouble(p[0]) * 1000);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&amp;", "&");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String httpDate() {
        return new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                .format(new Date());
    }
}
