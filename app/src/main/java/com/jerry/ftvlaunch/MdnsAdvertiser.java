package com.jerry.ftvlaunch;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 自己实现的 mDNS 广播：NsdManager 不支持超过 255 字节的 TXT 值（AirPlay 的 pk 有 512 个字符），
 * 这里按 RFC 6763 把长值拆成多段，自己应答查询 + 定时宣告。
 */
final class MdnsAdvertiser {

    private static final String TAG = "Mdns";
    private static final InetAddress GROUP;
    private static final int PORT = 5353;

    static {
        InetAddress g = null;
        try {
            g = InetAddress.getByName("224.0.0.251");
        } catch (Exception ignored) {
        }
        GROUP = g;
    }

    private final String instance;   // <mac>@<name>
    private final String service;    // _raop._tcp.local
    private final String host;       // ftvbox.local
    private final int port;
    private final String ip;
    private final List<String[]> txt = new ArrayList<>();

    private volatile boolean running;
    private MulticastSocket socket;

    MdnsAdvertiser(String instance, int port, String ip) {
        this.instance = instance;
        this.service = "_raop._tcp.local";
        this.host = "ftvbox.local";
        this.port = port;
        this.ip = ip;
    }

    void addTxt(String k, String v) {
        txt.add(new String[]{k, v});
    }

    void start() {
        running = true;
        new Thread(this::loop, "mdns").start();
        new Thread(this::announceLoop, "mdns-announce").start();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    private void loop() {
        try {
            socket = new MulticastSocket(PORT);
            socket.setReuseAddress(true);
            socket.joinGroup(new InetSocketAddress(GROUP, PORT),
                    NetworkInterface.getByInetAddress(InetAddress.getByName(ip)));
            byte[] buf = new byte[4096];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            while (running) {
                p.setLength(buf.length);
                socket.receive(p);
                byte[] data = new byte[p.getLength()];
                System.arraycopy(p.getData(), 0, data, 0, p.getLength());
                if (p.getPort() == PORT) {
                    handleQuery(data);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "mdns loop end", e);
        }
    }

    /** 解析查询里的名字，命中就应答。 */
    private void handleQuery(byte[] data) {
        if (data.length < 12) return;
        int qd = ((data[4] & 0xFF) << 8) | (data[5] & 0xFF);
        if (qd <= 0) return;
        int off = 12;
        boolean want = false;
        for (int i = 0; i < qd && off < data.length; i++) {
            StringBuilder name = new StringBuilder();
            off = readName(data, off, name);
            if (off + 4 > data.length) return;
            int type = ((data[off] & 0xFF) << 8) | (data[off + 1] & 0xFF);
            off += 4;
            String n = name.toString().toLowerCase();
            if ((n.equals(service) || n.equals(instanceName().toLowerCase())
                    || n.equals(host)) && (type == 12 || type == 16 || type == 33
                    || type == 1 || type == 255)) {
                want = true;
            }
        }
        if (want) sendResponse();
    }

    private String instanceName() {
        return instance + "." + service;
    }

    private void announceLoop() {
        while (running) {
            sendResponse();
            try {
                Thread.sleep(60000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void sendResponse() {
        try {
            byte[] msg = response();
            DatagramPacket p = new DatagramPacket(msg, msg.length, GROUP, PORT);
            MulticastSocket s = socket;
            if (s != null && !s.isClosed()) s.send(p);
        } catch (Exception e) {
            Log.e(TAG, "send failed", e);
        }
    }

    private byte[] response() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int answers = 4;
        out.write(0);
        out.write(0);           // ID
        out.write(0x84);        // flags: response + AA
        out.write(0x00);
        out.write(0);
        out.write(0);           // QDCOUNT
        out.write(0);
        out.write(answers);     // ANCOUNT
        out.write(0);
        out.write(0);           // NSCOUNT
        out.write(0);
        out.write(0);           // ARCOUNT

        // PTR _raop._tcp.local -> instance
        writeRecord(out, service, 12, false, 4500, encodeName(instanceName()));
        // SRV
        byte[] srv = new byte[6];
        srv[4] = (byte) ((port >> 8) & 0xFF);
        srv[5] = (byte) (port & 0xFF);
        ByteArrayOutputStream srvData = new ByteArrayOutputStream();
        srvData.write(srv, 0, 6);
        byte[] target = encodeName(host);
        srvData.write(target, 0, target.length);
        writeRecord(out, instanceName(), 33, true, 4500, srvData.toByteArray());
        // TXT
        writeRecord(out, instanceName(), 16, true, 4500, txtData());
        // A
        try {
            byte[] addr = InetAddress.getByName(ip).getAddress();
            writeRecord(out, host, 1, true, 4500, addr);
        } catch (Exception ignored) {
        }
        return out.toByteArray();
    }

    /** 单个 TXT 字符串最长 255 字节，长值按 RFC 6763 拆段。 */
    private byte[] txtData() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String[] kv : txt) {
            String full = kv[0] + "=" + kv[1];
            byte[] bytes = full.getBytes(StandardCharsets.UTF_8);
            int off = 0;
            boolean first = true;
            while (off < bytes.length) {
                int len = Math.min(255, bytes.length - off);
                // 第一段带 key=，后续段是续接
                if (first) {
                    out.write(len);
                    out.write(bytes, off, len);
                    off += len;
                    first = false;
                } else {
                    out.write(len);
                    out.write(bytes, off, len);
                    off += len;
                }
            }
        }
        return out.toByteArray();
    }

    private void writeRecord(ByteArrayOutputStream out, String name, int type,
                             boolean unique, int ttl, byte[] rdata) {
        byte[] n = encodeName(name);
        out.write(n, 0, n.length);
        out.write((type >> 8) & 0xFF);
        out.write(type & 0xFF);
        int clazz = 1 | (unique ? 0x8000 : 0);
        out.write((clazz >> 8) & 0xFF);
        out.write(clazz & 0xFF);
        out.write((ttl >> 24) & 0xFF);
        out.write((ttl >> 16) & 0xFF);
        out.write((ttl >> 8) & 0xFF);
        out.write(ttl & 0xFF);
        out.write((rdata.length >> 8) & 0xFF);
        out.write(rdata.length & 0xFF);
        out.write(rdata, 0, rdata.length);
    }

    private static byte[] encodeName(String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String label : name.split("\\.")) {
            if (label.isEmpty()) continue;
            byte[] b = label.getBytes(StandardCharsets.UTF_8);
            out.write(b.length);
            out.write(b, 0, b.length);
        }
        out.write(0);
        return out.toByteArray();
    }

    private static int readName(byte[] data, int off, StringBuilder sb) {
        int guard = 0;
        while (off < data.length && guard++ < 128) {
            int len = data[off] & 0xFF;
            if (len == 0) {
                off++;
                break;
            }
            if ((len & 0xC0) == 0xC0) { // 指针，简单跳过
                off += 2;
                break;
            }
            off++;
            if (off + len > data.length) break;
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(data, off, len, StandardCharsets.UTF_8));
            off += len;
        }
        return off;
    }
}
