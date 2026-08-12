package com.ms.webview.detect.hls;

import androidx.annotation.Nullable;

import com.ms.webview.core.Http;

import java.io.IOException;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Fetch helpers that always replay the headers captured when the WebView saw the URL. */
public final class HlsHttp {

    /** Carries the status code so callers can tell "denied" apart from "broken". */
    public static class StatusException extends IOException {
        public final int code;

        public StatusException(int code, String url) {
            super("HTTP " + code + " for " + url);
            this.code = code;
        }
    }

    private HlsHttp() {
    }

    public static String fetchText(String url, @Nullable Map<String, String> headers)
            throws IOException {
        Request.Builder b = new Request.Builder().url(url).get();
        Http.withCaptured(b, headers);
        try (Response resp = Http.client().newCall(b.build()).execute()) {
            if (!resp.isSuccessful()) throw new StatusException(resp.code(), url);
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty playlist body");
            return body.source().readString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public static byte[] fetchBytes(String url, @Nullable Map<String, String> headers,
                                    long byteStart, long byteLength) throws IOException {
        Request.Builder b = new Request.Builder().url(url).get();
        Http.withCaptured(b, headers);
        if (byteLength > 0 && byteStart >= 0) {
            b.header("Range", "bytes=" + byteStart + "-" + (byteStart + byteLength - 1));
        }
        try (Response resp = Http.client().newCall(b.build()).execute()) {
            if (resp.code() != 200 && resp.code() != 206) {
                throw new StatusException(resp.code(), url);
            }
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty body");
            return body.bytes();
        }
    }

    public static long sizeOf(String url, @Nullable Map<String, String> headers) {
        Request.Builder b = new Request.Builder().url(url).get().header("Range", "bytes=0-0");
        Http.withCaptured(b, headers);
        try (Response resp = Http.client().newCall(b.build()).execute()) {
            String contentRange = resp.header("Content-Range");
            if (contentRange != null) {
                int slash = contentRange.lastIndexOf('/');
                if (slash > 0) return Long.parseLong(contentRange.substring(slash + 1).trim());
            }
            String length = resp.header("Content-Length");
            return length == null ? -1 : Long.parseLong(length.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
