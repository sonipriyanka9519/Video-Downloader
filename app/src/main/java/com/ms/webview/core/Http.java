package com.ms.webview.core;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Shared OkHttp client and the header handling that decides whether a CDN answers us. */
public final class Http {

    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Mobile Safari/537.36";

    /**
     * Headers captured from the WebView that must not be replayed. Some are hop-by-hop and
     * OkHttp owns them; the Sec-Fetch family describes a browser navigation context that does
     * not match an out-of-band fetch, and several CDNs reject the mismatch outright.
     */
    private static final Set<String> BLOCKED = new HashSet<>(Arrays.asList(
            "host", "connection", "proxy-connection", "content-length", "transfer-encoding",
            "te", "trailer", "upgrade", "keep-alive",
            "accept-encoding", "range", "if-range", "if-modified-since", "if-none-match",
            "sec-fetch-site", "sec-fetch-mode", "sec-fetch-dest", "sec-fetch-user",
            "upgrade-insecure-requests", "purpose", "x-requested-with"));

    private static volatile OkHttpClient client;

    private Http() {
    }

    public static OkHttpClient client() {
        if (client == null) {
            synchronized (Http.class) {
                if (client == null) {
                    client = new OkHttpClient.Builder()
                            .connectTimeout(20, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .retryOnConnectionFailure(true)
                            .followRedirects(true)
                            .build();
                }
            }
        }
        return client;
    }

    /**
     * Applies the headers captured when the URL was first seen in the WebView. Without the
     * original Referer/Cookie/User-Agent most CDNs answer 403 to an out-of-band request.
     */
    public static Request.Builder withCaptured(@NonNull Request.Builder b,
                                               @Nullable Map<String, String> headers) {
        boolean hasUa = false;
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null) continue;
                if (BLOCKED.contains(k.toLowerCase(Locale.US))) continue;
                if (k.equalsIgnoreCase("User-Agent")) hasUa = true;
                b.header(k, v);
            }
        }
        if (!hasUa) b.header("User-Agent", DEFAULT_UA);
        return b;
    }

    /**
     * A second, more conservative header set to retry with after a 401/403/410.
     *
     * <p>Instagram and Facebook CDNs are picky in opposite directions depending on the URL:
     * some signed media URLs are rejected when a session cookie is attached, and several want
     * the site origin as Referer rather than the deep permalink the video was found on. This
     * keeps only the user agent and an origin-only Referer.
     */
    public static Map<String, String> relaxed(@Nullable Map<String, String> headers) {
        Map<String, String> out = new HashMap<>();
        String userAgent = null;
        String referer = null;

        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null) continue;
                if (e.getKey().equalsIgnoreCase("User-Agent")) userAgent = e.getValue();
                else if (e.getKey().equalsIgnoreCase("Referer")) referer = e.getValue();
            }
        }

        out.put("User-Agent", TextUtils.isEmpty(userAgent) ? DEFAULT_UA : userAgent);
        String origin = originOf(referer);
        if (origin != null) out.put("Referer", origin);
        return out;
    }

    /** Whether a status code is worth retrying with {@link #relaxed} headers. */
    public static boolean deniedByHeaders(int code) {
        return code == 401 || code == 403 || code == 410;
    }

    @Nullable
    public static String originOf(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            Uri u = Uri.parse(url);
            if (u.getScheme() == null || u.getHost() == null) return null;
            return u.getScheme() + "://" + u.getHost() + "/";
        } catch (Exception e) {
            return null;
        }
    }
}
