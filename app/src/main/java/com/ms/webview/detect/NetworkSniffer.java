package com.ms.webview.detect;

import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Layer 1. Observes every subresource the WebView fetches.
 *
 * <p>Two things this layer exists to capture that nothing else can:
 * the URLs of media that never appears in the DOM, and the exact request headers the page used.
 * Replaying those headers later is what keeps the CDN from answering 403.
 */
public class NetworkSniffer {

    private final MediaRegistry registry;
    /** Hand off immediately: this runs on the WebView's network thread for every request. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    public NetworkSniffer(MediaRegistry registry) {
        this.registry = registry;
    }

    public void inspect(WebResourceRequest request) {
        if (request == null) return;
        String method = request.getMethod();
        if (method != null && !method.equalsIgnoreCase("GET")) return;

        final String url;
        try {
            url = request.getUrl().toString();
        } catch (Exception e) {
            return;
        }

        final MediaKind kind = UrlClassifier.classify(url);
        if (kind == MediaKind.NONE) return;

        final Map<String, String> headers = new HashMap<>();
        try {
            Map<String, String> reqHeaders = request.getRequestHeaders();
            if (reqHeaders != null) headers.putAll(reqHeaders);
        } catch (Exception ignored) {
        }

        worker.execute(() -> {
            // getRequestHeaders() never includes Cookie, so pull it from the WebView's jar.
            try {
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);
            } catch (Exception ignored) {
            }
            if (!headers.containsKey("Referer") && !headers.containsKey("referer")) {
                String page = registry.pageUrl();
                if (page != null && !page.isEmpty()) headers.put("Referer", page);
            }
            registry.offerNetwork(url, kind, headers);
        });
    }
}
