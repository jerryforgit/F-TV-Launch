package com.jerry.ftvlaunch;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayDeque;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AirPlay 1 音频接收端（RAOP）：
 * mDNS 广播 _raop._tcp → RTSP 握手（RSA/AES）→ RTP 收流 → MediaCodec 解 ALAC → AudioTrack 播放。
 */
final class AirPlayServer {

    private static final String TAG = "AirPlay";
    private static final int RTSP_PORT = 5000;
    private volatile int airplay2Port;

    private static AirPlayServer instance;

    private final Context ctx;
    private PrivateKey privateKey;
    private String pkHex;
    private String mac;
    private volatile boolean running;

    static synchronized AirPlayServer get(Context c) {
        if (instance == null) instance = new AirPlayServer(c.getApplicationContext());
        return instance;
    }

    private AirPlayServer(Context c) {
        ctx = c;
    }

    void start() {
        if (running) return;
        running = true;
        loadKeys();
        mac = hardwareAddress();
        new Thread(() -> rtspLoop(RTSP_PORT), "airplay-rtsp").start();
        registerMdns();
    }

    // ---------------- 密钥 ----------------

    private void loadKeys() {
        SharedPreferences sp = ctx.getSharedPreferences("airplay", Context.MODE_PRIVATE);
        try {
            String priv = sp.getString("priv", null);
            String pub = sp.getString("pub", null);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv == null || pub == null) {
                KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
                g.initialize(2048);
                KeyPair kp = g.generateKeyPair();
                priv = Base64.encodeToString(kp.getPrivate().getEncoded(), Base64.NO_WRAP);
                pub = Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP);
                sp.edit().putString("priv", priv).putString("pub", pub).apply();
            }
            privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP)));
            PublicKey p = kf.generatePublic(new X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP)));
            java.security.interfaces.RSAPublicKey rp = (java.security.interfaces.RSAPublicKey) p;
            pkHex = leftPad(rp.getModulus().toString(16), 512);
        } catch (Exception e) {
            Log.e(TAG, "key init failed", e);
        }
    }

    private static String leftPad(String s, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = s.length(); i < len; i++) sb.append('0');
        return sb.append(s).toString();
    }

    private String hardwareAddress() {
        try {
            java.util.Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                byte[] hw = ni.getHardwareAddress();
                if (hw == null || hw.length < 6) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : hw) sb.append(String.format(Locale.US, "%02X", b));
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return "AABBCCDDEEFF";
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

    // ---------------- mDNS ----------------

    private void registerMdns() {
        try {
            MdnsAdvertiser mdns = new MdnsAdvertiser(mac + "@F TV 桌面", "_raop._tcp.local",
                    RTSP_PORT, localIp());
            mdns.addTxt("txtvers", "1");
            mdns.addTxt("ch", "2");
            mdns.addTxt("cn", "0,1");
            mdns.addTxt("et", "0");
            mdns.addTxt("sv", "false");
            mdns.addTxt("da", "true");
            mdns.addTxt("sr", "44100");
            mdns.addTxt("ss", "16");
            mdns.addTxt("pw", "false");
            mdns.addTxt("vn", "3");
            mdns.addTxt("tp", "UDP");
            mdns.addTxt("md", "0,1,2");
            mdns.addTxt("sm", "false");
            mdns.addTxt("ek", "1");
            mdns.addTxt("vs", "130.14");
            mdns.addTxt("am", "F TV 桌面");
            mdns.addTxt("sf", "0x4");
            if (pkHex != null) mdns.addTxt("pk", pkHex);
            mdns.start();
            Log.i(TAG, "mDNS started, pk=" + (pkHex == null ? 0 : pkHex.length()));
        } catch (Exception e) {
            Log.e(TAG, "mdns error", e);
        }
    }

    /** AirPlay 2 的服务记录（iOS 17 走这个通道）。 */
    private void registerAirPlay2Mdns() {
        try {
            String macColon = mac.replaceAll("(..)(?!$)", "$1:");
            MdnsAdvertiser mdns = new MdnsAdvertiser("F TV 桌面", "_airplay._tcp.local",
                    airplay2Port, localIp());
            mdns.addTxt("deviceid", macColon);
            mdns.addTxt("features", "0x5A7FFFF7,0x1E");
            mdns.addTxt("flags", "0x4");
            mdns.addTxt("model", "AppleTV3,2");
            mdns.addTxt("pi", "b8b7c5a1-2f3c-4d5e-9a1b-3c4d5e6f7a8b");
            mdns.addTxt("srcvers", "220.68");
            mdns.addTxt("vv", "2");
            if (pkHex != null) mdns.addTxt("pk", pkHex);
            mdns.start();
            Log.i(TAG, "AirPlay2 mDNS started");
        } catch (Exception e) {
            Log.e(TAG, "airplay2 mdns error", e);
        }
    }

    // ---------------- RTSP ----------------

    /** AirPlay 2 用随机空闲端口（7000/7100 已被盒子上的其他投屏 App 占用）。 */
    private void startAirplay2() {
        try {
            ServerSocket ss = new ServerSocket(0);
            airplay2Port = ss.getLocalPort();
            Log.i(TAG, "AirPlay2 listening " + airplay2Port);
            new Thread(() -> serve(ss, "airplay2-rtsp"), "airplay2-rtsp").start();
            registerAirPlay2Mdns();
        } catch (Exception e) {
            Log.e(TAG, "airplay2 bind failed", e);
        }
    }

    private void serve(ServerSocket server, String name) {
        try {
            while (running) {
                Socket s = server.accept();
                new Thread(() -> handleRtsp(s), name).start();
            }
        } catch (IOException ignored) {
        }
    }

    private void rtspLoop(int port) {
        try {
            ServerSocket server = new ServerSocket(port);
            server.setReuseAddress(true);
            Log.i(TAG, "RTSP listening " + port);
            while (running) {
                Socket s = server.accept();
                new Thread(() -> handleRtsp(s), "airplay-conn").start();
            }
        } catch (IOException e) {
            Log.e(TAG, "rtsp bind failed", e);
        }
    }

    private void handleRtsp(Socket socket) {
        Session session = new Session(socket);
        try (Socket s = socket) {
            s.setSoTimeout(0);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            while (true) {
                String header = readHeader(in);
                if (header.isEmpty()) break;
                String[] lines = header.split("\r\n");
                String[] first = lines[0].split(" ");
                if (first.length < 3) break;
                String method = first[0];
                String uri = first[1];
                int len = 0;
                String cseq = "0";
                String challenge = null;
                for (String line : lines) {
                    int c = line.indexOf(':');
                    if (c <= 0) continue;
                    String name = line.substring(0, c).trim().toLowerCase(Locale.US);
                    String value = line.substring(c + 1).trim();
                    if (name.equals("content-length")) len = Integer.parseInt(value);
                    else if (name.equals("cseq")) cseq = value;
                    else if (name.equals("apple-challenge")) challenge = value;
                }
                byte[] body = len > 0 ? readFully(in, len) : new byte[0];
                Log.d(TAG, "RTSP " + method + " " + uri + " len=" + len);
                if (body.length > 0 && (uri.contains("pair") || uri.contains("auth")
                        || uri.contains("info"))) {
                    Log.d(TAG, "body[" + uri + "]: " + hex(body, Math.min(body.length, 64)));
                }
                String reply = dispatch(session, method, uri, cseq, challenge, body);
                out.write(reply.getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (Exception e) {
            Log.d(TAG, "conn end: " + e);
        } finally {
            session.close();
        }
    }

    private String dispatch(Session sess, String method, String uri, String cseq,
                            String challenge, byte[] body) {
        // AirPlay 2 的 HAP 配对（curve25519）我们没实现：明确拒绝，iOS 会自动回退到 AirPlay 1
        if (method.equals("POST") && (uri.startsWith("/auth-setup")
                || uri.startsWith("/pair-setup") || uri.startsWith("/pair-verify"))) {
            Log.i(TAG, "reject HAP pairing " + uri);
            return "RTSP/1.0 403 Forbidden\r\nCSeq: " + cseq
                    + "\r\nServer: AirTunes/130.14\r\n\r\n";
        }
        switch (method) {
            case "OPTIONS": {
                String extra = "";
                if (challenge != null) {
                    String resp = signChallenge(challenge);
                    if (resp != null) extra = "Apple-Response: " + resp + "\r\n";
                }
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                        + "Server: AirTunes/130.14\r\n"
                        + "Public: ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, "
                        + "GET_PARAMETER, SET_PARAMETER\r\n"
                        + "Audio-Jack-Status: connected; type=analog\r\n"
                        + extra + "\r\n";
            }
            case "ANNOUNCE": {
                String sdp = new String(body, StandardCharsets.UTF_8);
                String keyB64 = sdpValue(sdp, "rsaaeskey");
                String ivB64 = sdpValue(sdp, "aesiv");
                Log.d(TAG, "ANNOUNCE rsaaeskey=" + (keyB64 != null) + " aesiv=" + (ivB64 != null));
                if (keyB64 != null && ivB64 != null) {
                    byte[] aes = decryptAesKey(keyB64);
                    if (aes != null && aes.length == 16) {
                        sess.aesKey = aes;
                        sess.aesIv = Base64.decode(ivB64, Base64.DEFAULT);
                        Log.i(TAG, "AES key ready, iv=" + sess.aesIv.length);
                    } else {
                        Log.e(TAG, "AES key decrypt failed");
                    }
                }
                sess.sessionId = String.valueOf(System.currentTimeMillis() / 1000);
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
            }
            case "SETUP": {
                sess.openAudio();
                sess.sessionId = String.valueOf(System.currentTimeMillis() / 1000);
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                        + "Transport: RTP/AVP/UDP;unicast;mode=record;"
                        + "server_port=" + sess.rtpPort + ";control_port=" + sess.controlPort
                        + ";timing_port=" + sess.timingPort + "\r\n"
                        + "Session: " + sess.sessionId + "\r\n\r\n";
            }
            case "RECORD": {
                sess.startAudio();
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                        + "Audio-Latency: 0\r\n"
                        + "Session: " + sess.sessionId + "\r\n\r\n";
            }
            case "FLUSH":
                sess.flushAudio();
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n"
                        + "Session: " + sess.sessionId + "\r\n\r\n";
            case "TEARDOWN":
                sess.stopAudio();
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
            case "SET_PARAMETER": {
                String text = new String(body, StandardCharsets.UTF_8);
                Log.d(TAG, "SET_PARAMETER " + text.replace("\n", " | "));
                String vol = paramLine(text, "volume:");
                if (vol != null) sess.setVolume(vol);
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
            }
            case "GET_PARAMETER":
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
            case "PAUSE":
                sess.pauseAudio();
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
            default:
                return "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
        }
    }

    private String signChallenge(String challengeB64) {
        try {
            byte[] challenge = Base64.decode(challengeB64, Base64.DEFAULT);
            byte[] ip = InetAddress.getByName(localIp()).getAddress();
            byte[] macBytes = new byte[6];
            for (int i = 0; i < 6; i++) {
                macBytes[i] = (byte) Integer.parseInt(mac.substring(i * 2, i * 2 + 2), 16);
            }
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(privateKey);
            sig.update(challenge);
            sig.update(ip);
            sig.update(macBytes);
            byte[] out = sig.sign();
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "challenge sign failed", e);
            return null;
        }
    }

    private byte[] decryptAesKey(String b64) {
        try {
            byte[] enc = Base64.decode(b64, Base64.DEFAULT);
            try {
                Cipher c = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
                c.init(Cipher.DECRYPT_MODE, privateKey);
                return c.doFinal(enc);
            } catch (Exception oaep) {
                Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                c.init(Cipher.DECRYPT_MODE, privateKey);
                return c.doFinal(enc);
            }
        } catch (Exception e) {
            Log.e(TAG, "rsa decrypt failed", e);
            return null;
        }
    }

    private static String sdpValue(String sdp, String key) {
        for (String line : sdp.split("\r\n")) {
            if (line.startsWith("a=" + key + ":")) return line.substring(key.length() + 3).trim();
        }
        return null;
    }

    private static String paramLine(String text, String key) {
        for (String line : text.split("[\r\n]+")) {
            if (line.startsWith(key)) return line.substring(key.length()).trim();
        }
        return null;
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

    // ---------------- 会话 / 音频 ----------------

    private final class Session {
        final Socket socket;
        final byte[] sessionIdBytes = null;
        String sessionId = "1";
        volatile byte[] aesKey;
        volatile byte[] aesIv;
        int rtpPort, controlPort, timingPort;
        DatagramSocket rtpSocket, controlSocket, timingSocket;
        AudioSink sink;
        Thread rtpThread;

        Session(Socket s) {
            socket = s;
        }

        void openAudio() {
            try {
                rtpSocket = new DatagramSocket();
                controlSocket = new DatagramSocket();
                timingSocket = new DatagramSocket();
                rtpPort = rtpSocket.getLocalPort();
                controlPort = controlSocket.getLocalPort();
                timingPort = timingSocket.getLocalPort();
                Log.i(TAG, "ports rtp=" + rtpPort + " ctl=" + controlPort + " time=" + timingPort);
                rtpThread = new Thread(this::rtpLoop, "airplay-rtp");
                rtpThread.start();
                new Thread(this::controlLoop, "airplay-ctl").start();
                new Thread(this::timingLoop, "airplay-time").start();
            } catch (Exception e) {
                Log.e(TAG, "open audio failed", e);
            }
        }

        private void rtpLoop() {
            byte[] buf = new byte[4096];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running && !rtpSocket.isClosed()) {
                try {
                    p.setLength(buf.length);
                    rtpSocket.receive(p);
                    byte[] data = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, data, 0, p.getLength());
                    onRtpPacket(data);
                } catch (Exception e) {
                    break;
                }
            }
        }

        private void controlLoop() {
            byte[] buf = new byte[2048];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running && !controlSocket.isClosed()) {
                try {
                    p.setLength(buf.length);
                    controlSocket.receive(p);
                } catch (Exception e) {
                    break;
                }
            }
        }

        private void timingLoop() {
            byte[] buf = new byte[512];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running && !timingSocket.isClosed()) {
                try {
                    p.setLength(buf.length);
                    timingSocket.receive(p);
                    // 原样回一个最小的 timing 响应，避免发送端判定超时
                    byte[] req = new byte[p.getLength()];
                    System.arraycopy(p.getData(), 0, req, 0, p.getLength());
                    byte[] resp = new byte[32];
                    resp[0] = (byte) 0x80;
                    resp[1] = (byte) 0xd3;
                    resp[2] = 0x00;
                    resp[3] = 0x07;
                    if (req.length > 8) System.arraycopy(req, 24, resp, 8, 8);
                    timingSocket.send(new DatagramPacket(resp, resp.length, p.getAddress(), p.getPort()));
                } catch (Exception e) {
                    break;
                }
            }
        }

        private void onRtpPacket(byte[] packet) {
            if (packet.length < 13) return;
            int seq = ((packet[2] & 0xFF) << 8) | (packet[3] & 0xFF);
            int ts = ((packet[4] & 0xFF) << 24) | ((packet[5] & 0xFF) << 16)
                    | ((packet[6] & 0xFF) << 8) | (packet[7] & 0xFF);
            boolean marker = (packet[1] & 0x80) != 0;
            int payloadOffset = 12;
            if (packet.length > 12 && (packet[0] & 0x20) != 0) {
                int csrc = packet[0] & 0x0F;
                payloadOffset += csrc * 4;
            }
            int payloadLen = packet.length - payloadOffset;
            if (payloadLen <= 0) return;
            byte[] payload = new byte[payloadLen];
            System.arraycopy(packet, payloadOffset, payload, 0, payloadLen);
            if (aesKey != null && aesIv != null) {
                payload = aesDecrypt(payload, aesKey, aesIv);
            }
            if (payload == null || payload.length <= 4) return;
            if (seq % 100 == 0 || seq < 3) {
                Log.d(TAG, "rtp seq=" + seq + " ts=" + ts + " m=" + marker
                        + " len=" + payload.length + " head=" + hex(payload, 6));
            }
            if (sink != null) sink.push(payload, seq, ts);
        }

        void startAudio() {
            if (sink == null) sink = new AudioSink();
            sink.start();
            Log.i(TAG, "RECORD start");
        }

        void pauseAudio() {
            if (sink != null) sink.pause();
        }

        void flushAudio() {
            if (sink != null) sink.flush();
        }

        void stopAudio() {
            if (sink != null) {
                sink.stop();
                sink = null;
            }
        }

        void setVolume(String v) {
            try {
                float db = Float.parseFloat(v);
                if (sink != null) sink.setVolumeDb(db);
            } catch (Exception ignored) {
            }
        }

        void close() {
            stopAudio();
            try {
                if (rtpSocket != null) rtpSocket.close();
                if (controlSocket != null) controlSocket.close();
                if (timingSocket != null) timingSocket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static byte[] aesDecrypt(byte[] data, byte[] key, byte[] iv) {
        try {
            int len = data.length & ~15;
            if (len <= 0) return null;
            Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return c.doFinal(data, 0, len);
        } catch (Exception e) {
            return null;
        }
    }

    private static String hex(byte[] b, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, b.length); i++) {
            sb.append(String.format(Locale.US, "%02x", b[i]));
        }
        return sb.toString();
    }

    // ---------------- ALAC 播放 ----------------

    private final class AudioSink {
        private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        private AudioTrack track;
        private long decoder;
        private Thread worker;
        private volatile boolean alive;
        private volatile boolean paused;
        private volatile float gain = 1f;
        private int chosenOffset = 4;
        private boolean offsetChecked;

        void start() {
            if (alive) return;
            alive = true;
            try {
                decoder = AlacNative.create(alacCookie());
                int ch = decoder != 0 ? AlacNative.channels(decoder) : 2;
                int frame = decoder != 0 ? AlacNative.frameLength(decoder) : 352;
                Log.i(TAG, "ALAC decoder=" + decoder + " ch=" + ch + " frame=" + frame);
                if (decoder == 0) {
                    Log.e(TAG, "ALAC decoder init failed");
                }
                int minBuf = AudioTrack.getMinBufferSize(44100,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                track = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, 44100,
                        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                        Math.max(minBuf * 4, 8192), AudioTrack.MODE_STREAM);
                track.play();
                worker = new Thread(this::loop, "airplay-audio");
                worker.start();
                Log.i(TAG, "audio sink started, minBuf=" + minBuf);
            } catch (Exception e) {
                Log.e(TAG, "audio init failed", e);
            }
        }

        void push(byte[] payload, int seq, int ts) {
            synchronized (queue) {
                if (queue.size() > 200) queue.pollFirst();
                queue.addLast(payload);
                queue.notifyAll();
            }
        }

        private void loop() {
            byte[] frameBuf = new byte[8192];
            byte[] pcm = new byte[8192];
            int lastLog = 0;
            while (alive) {
                byte[] packet;
                synchronized (queue) {
                    if (queue.isEmpty()) {
                        try {
                            queue.wait(200);
                        } catch (InterruptedException ignored) {
                        }
                        continue;
                    }
                    packet = queue.pollFirst();
                }
                try {
                    if (decoder == 0) continue;
                    int samples = -1;
                    if (!offsetChecked) {
                        for (int off : new int[]{4, 0, 8}) {
                            if (packet.length <= off + 4) continue;
                            int len = packet.length - off;
                            System.arraycopy(packet, off, frameBuf, 0, len);
                            samples = AlacNative.decode(decoder, frameBuf, len, pcm);
                            Log.d(TAG, "try offset " + off + " -> samples=" + samples);
                            if (samples > 0) {
                                chosenOffset = off;
                                offsetChecked = true;
                                break;
                            }
                        }
                    } else {
                        int len = Math.max(0, packet.length - chosenOffset);
                        System.arraycopy(packet, chosenOffset, frameBuf, 0, len);
                        samples = AlacNative.decode(decoder, frameBuf, len, pcm);
                    }
                    if (samples > 0 && !paused) {
                        int bytes = Math.min(samples * 2 * 2, pcm.length);
                        track.write(pcm, 0, bytes);
                        if (lastLog++ % 200 == 0) {
                            Log.d(TAG, "played samples=" + samples + " offset=" + chosenOffset);
                        }
                    } else if (samples < 0 && lastLog++ % 50 == 0) {
                        Log.w(TAG, "alac decode err " + samples + " len=" + packet.length
                                + " head=" + hex(packet, 8));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "decode error", e);
                }
            }
        }

        void pause() {
            paused = true;
            try {
                track.pause();
            } catch (Exception ignored) {
            }
        }

        void flush() {
            synchronized (queue) {
                queue.clear();
            }
            offsetChecked = false;
        }

        void setVolumeDb(float db) {
            gain = (float) Math.pow(10, db / 20.0);
            try {
                track.setVolume(gain);
            } catch (Exception ignored) {
            }
        }

        void stop() {
            alive = false;
            try {
                if (worker != null) worker.join(500);
            } catch (Exception ignored) {
            }
            try {
                if (track != null) {
                    track.stop();
                    track.release();
                }
            } catch (Exception ignored) {
            }
            if (decoder != 0) {
                AlacNative.destroy(decoder);
                decoder = 0;
            }
        }

        private byte[] alacCookie() {
            ByteBuffer b = ByteBuffer.allocate(36);
            b.putInt(36);
            b.put("alac".getBytes(StandardCharsets.US_ASCII));
            b.putInt(0);
            b.putInt(352);
            b.put((byte) 0);
            b.put((byte) 16);
            b.put((byte) 40);
            b.put((byte) 10);
            b.put((byte) 14);
            b.put((byte) 2);
            b.putShort((short) 255);
            b.putInt(0);
            b.putInt(0);
            b.putInt(44100);
            return b.array();
        }
    }
}
