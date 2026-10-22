package com.frame.home;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Minimal blocking HTTP helpers. Call off the main thread. */
final class Http {
    private Http() {}

    static String get(String url) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(url, out);
        return out.toString("UTF-8");
    }

    /** Downloads to a temp file first so a failed download never leaves a broken image. */
    static void download(String url, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            copy(url, out);
        }
        if (!tmp.renameTo(dest)) {
            tmp.delete();
            throw new IOException("rename failed: " + dest);
        }
    }

    private static void copy(String url, OutputStream out) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "Frame/0.1 (Android TV)");
        try {
            if (conn.getResponseCode() != 200) throw new IOException("HTTP " + conn.getResponseCode() + " " + url);
            try (InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }
}
