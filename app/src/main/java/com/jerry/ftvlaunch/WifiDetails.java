package com.jerry.ftvlaunch;

import android.content.Context;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 网络详情面板的数据填充。 */
final class WifiDetails {

    private WifiDetails() {
    }

    static void fill(Context ctx, TextView title, LinearLayout rows) {
        rows.removeAllViews();
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        DhcpInfo dhcp = wm == null ? null : wm.getDhcpInfo();

        String ssid = cleanSsid(info == null ? null : info.getSSID());
        boolean connected = info != null && info.getNetworkId() >= 0;
        title.setText(ssid != null ? ssid : (connected ? "已连接（读取名称需定位权限）" : "未连接 Wi-Fi"));

        boolean online = isOnline(ctx);
        add(ctx, rows, "互联网连接", online ? "已连接" : "未连接");

        String ipv6 = findIp(true);
        String ipv4 = findIp(false);
        if (ipv6 != null) add(ctx, rows, "IP 地址 (IPv6)", ipv6);
        if (ipv4 != null) add(ctx, rows, "IP 地址", ipv4);
        else if (dhcp != null && dhcp.ipAddress != 0) add(ctx, rows, "IP 地址", format(dhcp.ipAddress));

        String mac = macAddress();
        if (mac == null || mac.equals("02:00:00:00:00:00")) mac = null;
        add(ctx, rows, "设备 MAC 地址", mac == null ? "系统限制，应用不可见" : mac);

        if (info != null) {
            int rssi = info.getRssi();
            add(ctx, rows, "信号强度", signalText(rssi));
            add(ctx, rows, "RSSI", String.valueOf(rssi));
            add(ctx, rows, "Frequency", frequencyText(info));
            add(ctx, rows, "Link speed", info.getLinkSpeed() + " Mbps");
        }

        add(ctx, rows, "代理设置", proxyText());
        add(ctx, rows, "IP 设置", dhcp != null && dhcp.ipAddress != 0 ? "DHCP" : "—");
    }

    private static void add(Context ctx, LinearLayout rows, String label, String value) {
        LinearLayout block = new LinearLayout(ctx);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(ctx, 10), 0, dp(ctx, 10));

        TextView l = new TextView(ctx);
        l.setText(label);
        l.setTextColor(Color.parseColor("#FF555555"));
        l.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(ctx, 28));
        block.addView(l);

        TextView v = new TextView(ctx);
        v.setText(value);
        v.setTextColor(Color.parseColor("#FF8A8A8A"));
        v.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(ctx, 24));
        v.setPadding(0, dp(ctx, 6), 0, 0);
        v.setGravity(Gravity.START);
        block.addView(v);

        rows.addView(block);
    }

    private static int dp(Context ctx, float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }

    private static String cleanSsid(String ssid) {
        if (ssid == null) return null;
        ssid = ssid.trim();
        if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }
        if (ssid.isEmpty() || ssid.contains("unknown") || ssid.equals("0x")) return null;
        return ssid;
    }

    private static boolean isOnline(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo ni = cm.getActiveNetworkInfo();
        return ni != null && ni.isConnected();
    }

    private static String signalText(int rssi) {
        if (rssi >= -50) return "极强";
        if (rssi >= -60) return "强";
        if (rssi >= -70) return "一般";
        if (rssi >= -80) return "弱";
        return "很弱";
    }

    private static String frequencyText(WifiInfo info) {
        try {
            int f = info.getFrequency();
            if (f <= 0) return "—";
            String band = f >= 5000 ? "5 GHz" : "2.4 GHz";
            return String.format(Locale.US, "%s (%d MHz)", band, f);
        } catch (RuntimeException e) {
            return "—";
        }
    }

    private static String proxyText() {
        String host = System.getProperty("http.proxyHost");
        String port = System.getProperty("http.proxyPort");
        if (host == null || host.isEmpty()) return "无";
        return host + (port == null ? "" : ":" + port);
    }

    private static String format(int ip) {
        return String.format(Locale.US, "%d.%d.%d.%d",
                ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
    }

    private static String findIp(boolean v6) {
        try {
            List<NetworkInterface> nics = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface nic : nics) {
                if (!nic.isUp()) continue;
                String n = nic.getName();
                if (!n.startsWith("wlan") && !n.startsWith("eth")) continue;
                for (InetAddress addr : Collections.list(nic.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;
                    if (v6 && addr instanceof Inet6Address) {
                        String h = addr.getHostAddress();
                        int p = h.indexOf('%');
                        return p > 0 ? h.substring(0, p) : h;
                    }
                    if (!v6 && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String macAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.getName().startsWith("wlan")) continue;
                byte[] mac = nic.getHardwareAddress();
                if (mac == null) continue;
                StringBuilder sb = new StringBuilder();
                for (byte b : mac) {
                    if (sb.length() > 0) sb.append(':');
                    sb.append(String.format(Locale.US, "%02x", b));
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    static boolean forgetCurrent(Context ctx) {
        WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return false;
        WifiInfo info = wm.getConnectionInfo();
        if (info == null || info.getNetworkId() < 0) return false;
        boolean ok = wm.removeNetwork(info.getNetworkId());
        try {
            wm.saveConfiguration();
        } catch (RuntimeException ignored) {
        }
        return ok;
    }
}
