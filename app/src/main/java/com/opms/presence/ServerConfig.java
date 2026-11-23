package com.opms.presence;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Where the OPMS server is (saved on the phone), and a quick check that it answers. */
final class ServerConfig {
    private static final String PREFS = "opms";
    private static final String KEY_BASE = "server_base";

    private ServerConfig() {}

    /** The saved server address, e.g. "http://192.168.1.10:4000", or null before setup. */
    static String get(Context context) {
        return prefs(context).getString(KEY_BASE, null);
    }

    static void save(Context context, String base) {
        prefs(context).edit().putString(KEY_BASE, base).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Turns what the person typed into "scheme://host[:port]".
     * "192.168.1.10:4000", "http://192.168.1.10:4000/employee/" and "https://opms.example.com" all work.
     * Returns null if it isn't an address.
     */
    static String normalize(String typed) {
        if (typed == null) return null;
        String s = typed.trim();
        if (s.isEmpty()) return null;
        if (!s.matches("(?i)^https?://.*")) s = "http://" + s;
        Uri uri = Uri.parse(s);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isEmpty() || host.contains(" ")) return null;
        if (!scheme.equals("http") && !scheme.equals("https")) return null;
        int port = uri.getPort();
        // IPv6 literals need their brackets back.
        String hostPart = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return scheme + "://" + hostPart + (port > 0 ? ":" + port : "");
    }

    static String portalUrl(String base) {
        return base + "/employee/";
    }

    /** True when the link stays on the OPMS server (same scheme, host and port). */
    static boolean sameOrigin(String base, Uri url) {
        if (base == null || url == null) return false;
        Uri b = Uri.parse(base);
        return eq(b.getScheme(), url.getScheme()) && eq(b.getHost(), url.getHost()) && port(b) == port(url);
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static int port(Uri u) {
        if (u.getPort() > 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }

    /** Asks the server's health check. Runs the network call on the calling thread: never call it on the UI thread. */
    static boolean ping(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/healthz").openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return false;
            String body = readAll(c.getInputStream());
            return body.contains("\"ok\"");
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > 1_000_000) break; // a status reply is a few KB
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
