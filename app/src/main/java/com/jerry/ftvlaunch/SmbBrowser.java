package com.jerry.ftvlaunch;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/** 极简 SMB 客户端：列目录、下载文件。账号密码可空（匿名/guest）。 */
final class SmbBrowser {

    private SmbBrowser() {
    }

    static CIFSContext context(String user, String pass) {
        try {
            PropertyConfiguration cfg = new PropertyConfiguration(System.getProperties());
            BaseContext base = new BaseContext(cfg);
            if (user == null || user.trim().isEmpty()) {
                return base.withAnonymousCredentials();
            }
            return base.withCredentials(new NtlmPasswordAuthenticator("", user.trim(), pass == null ? "" : pass));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static SmbFile[] list(CIFSContext ctx, String url) throws Exception {
        SmbFile dir = new SmbFile(url, ctx);
        SmbFile[] items = dir.listFiles();
        return items == null ? new SmbFile[0] : items;
    }

    static void download(CIFSContext ctx, String url, File dst) throws Exception {
        SmbFile src = new SmbFile(url, ctx);
        try (InputStream in = src.getInputStream();
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    static String normalize(String url) {
        String u = url.trim();
        if (!u.startsWith("smb://")) u = "smb://" + u;
        if (!u.endsWith("/")) u = u + "/";
        return u;
    }
}
