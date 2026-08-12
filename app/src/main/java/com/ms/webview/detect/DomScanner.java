package com.ms.webview.detect;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ms.webview.core.Formats;
import com.ms.webview.core.Http;
import com.ms.webview.detect.extract.Extractors;
import com.ms.webview.detect.extract.FoundMedia;
import com.ms.webview.detect.extract.JsonMediaWalker;
import com.ms.webview.detect.extract.SiteExtractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the JavaScript bridge and both injected scripts.
 *
 * <p>{@code vd_scan.js} walks the DOM and captures frames from the playing video;
 * {@code vd_hook.js} patches fetch/XHR so the platforms that play through MediaSource still
 * give up their real media URLs. Both post to the same bridge and are dispatched here.
 */
public class DomScanner {

    private static final String TAG = "DomScanner";
    private static final String BRIDGE = "__vdBridge";
    private static final Set<String> ALL_ORIGINS = Collections.singleton("*");

    private final MediaRegistry registry;
    private final String scanScript;
    private final String hookScript;
    /** JSON walking is heavier than the DOM payloads, so it gets its own thread. */
    private final ExecutorService domWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService jsonWorker = Executors.newSingleThreadExecutor();

    private String userAgent = Http.DEFAULT_UA;

    public DomScanner(Context context, MediaRegistry registry) {
        this.registry = registry;
        this.scanScript = readAsset(context, "vd_scan.js");
        this.hookScript = readAsset(context, "vd_hook.js");
    }

    public void install(WebView webView) {
        try {
            String ua = webView.getSettings().getUserAgentString();
            if (!TextUtils.isEmpty(ua)) userAgent = ua;
        } catch (Exception ignored) {
        }
        // Page resolvers fetch outside the WebView and should look like the same browser.
        registry.setUserAgent(userAgent);

        boolean bridged = false;
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                // "*" is unavoidable in a general-purpose browser. The bridge is safe to expose
                // because it is write-only into the registry: a page can claim a media URL
                // exists, which is all it could already do by embedding a <video>.
                WebViewCompat.addWebMessageListener(webView, BRIDGE, ALL_ORIGINS,
                        (view, message, sourceOrigin, isMainFrame, replyProxy) ->
                                handle(message.getData()));
                bridged = true;
            } catch (Exception e) {
                Log.w(TAG, "WebMessageListener unavailable, falling back", e);
            }
        }
        if (!bridged) {
            webView.addJavascriptInterface(new LegacyBridge(), BRIDGE);
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try {
                // The hook only works if it patches fetch before the page's own scripts run,
                // so document start is not an optimisation here — it is the whole mechanism.
                WebViewCompat.addDocumentStartJavaScript(webView, hookScript, ALL_ORIGINS);
                WebViewCompat.addDocumentStartJavaScript(webView, scanScript, ALL_ORIGINS);
            } catch (Exception e) {
                Log.w(TAG, "Document start script unavailable", e);
            }
        }
    }

    /** Safe to call repeatedly; both scripts guard themselves against double installation. */
    public void scanNow(WebView webView) {
        if (!TextUtils.isEmpty(hookScript)) evaluate(webView, hookScript);
        if (!TextUtils.isEmpty(scanScript)) evaluate(webView, scanScript);
    }

    private void evaluate(WebView webView, String script) {
        try {
            webView.evaluateJavascript(script, null);
        } catch (Exception e) {
            Log.w(TAG, "Injection failed", e);
        }
    }

    private class LegacyBridge {
        @JavascriptInterface
        public void postMessage(String json) {
            handle(json);
        }
    }

    // ------------------------------------------------------------------ dispatch

    private void handle(String json) {
        if (TextUtils.isEmpty(json)) return;

        // Peek at the type before deciding which worker pays for the parse.
        boolean isJsonPayload = json.length() > 24 && json.regionMatches(
                true, 0, "{\"type\":\"json\"", 0, 14);

        ExecutorService worker = isJsonPayload ? jsonWorker : domWorker;
        worker.execute(() -> {
            try {
                dispatch(json);
            } catch (Exception e) {
                Log.w(TAG, "Bad payload", e);
            }
        });
    }

    private void dispatch(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        String type = str(obj, "type");
        if (type == null) return;

        switch (type) {
            case "media":
                handleDomMedia(obj);
                break;
            case "json":
                handleApiResponse(obj);
                break;
            case "playing":
                handlePlaying(obj);
                break;
            case "frame":
                handleFrame(obj);
                break;
            case "mse":
                registry.noteMediaSource();
                break;
            case "oversize":
                // A payload too large to carry across the bridge. Worth a line because the
                // symptom it causes is indistinguishable from a video that simply has one
                // quality: the ladder lives in the body that was dropped.
                Log.i(MediaRegistry.DIAG, "payload too large to read: " + num(obj, "size")
                        + "B from " + Formats.hostOf(str(obj, "url")));
                break;
            default:
                break;
        }
    }

    private void handleDomMedia(JsonObject obj) {
        JsonArray items = obj.getAsJsonArray("items");
        if (items == null) return;

        for (JsonElement el : items) {
            if (!el.isJsonObject()) continue;
            JsonObject it = el.getAsJsonObject();

            String url = str(it, "url");
            if (TextUtils.isEmpty(url)) continue;
            // Playing through MediaSource. Recovering these is the hook's job, not the DOM's.
            if (url.startsWith("blob:") || url.startsWith("data:")) continue;
            if (!url.startsWith("http")) continue;

            MediaKind kind = UrlClassifier.classify(url);
            if (kind == MediaKind.NONE) {
                // It was in a <video> element, so it is media whatever the URL looks like.
                if ("video".equals(str(it, "src"))) kind = MediaKind.UNKNOWN;
                else continue;
            }

            registry.offerDom(url, kind, str(it, "poster"), num(it, "dur"),
                    (int) num(it, "w"), (int) num(it, "h"), str(it, "title"), str(it, "hint"),
                    headersFor(url, false));
        }
    }

    /**
     * Runs the site extractors over a response the page fetched. This is what makes Instagram,
     * Facebook, X and ShareChat work: their media URLs exist only in these payloads.
     */
    private void handleApiResponse(JsonObject obj) {
        String body = str(obj, "body");
        if (TextUtils.isEmpty(body)) return;

        // The response's own host matters as much as the page's: an embedded player fetches its
        // config from its own domain.
        List<SiteExtractor> extractors = Extractors.forHosts(
                Formats.hostOf(registry.pageUrl()), Formats.hostOf(str(obj, "url")));
        if (extractors.isEmpty()) return;

        List<FoundMedia> found = JsonMediaWalker.walk(body, registry.pageUrl(), extractors);
        if (!found.isEmpty()) {
            Log.i(MediaRegistry.DIAG, "extracted " + found.size() + " from "
                    + Formats.hostOf(str(obj, "url")) + " (" + extractors.size() + " readers)");
        }
        for (FoundMedia media : found) {
            // Byte-range slices and images reach us through the same generic key names as real
            // videos, and neither is ever worth offering.
            if (UrlClassifier.isImage(media.url) || UrlClassifier.isPartialFetch(media.url)) {
                continue;
            }
            if (media.kind == MediaKind.PROGRESSIVE || media.kind == MediaKind.UNKNOWN) {
                // Trust the shape of the URL over the extractor's default.
                MediaKind byUrl = UrlClassifier.classify(media.url);
                if (byUrl == MediaKind.HLS || byUrl == MediaKind.DASH) media.kind = byUrl;
                else media.kind = MediaKind.PROGRESSIVE;
            }
            // Media pulled from an API response is fetched with the site origin as Referer:
            // signed CDN URLs are commonly checked against the origin, not the permalink.
            registry.offerExtracted(media, headersFor(media.url, true));
        }
    }

    private void handlePlaying(JsonObject obj) {
        JsonObject video = obj.getAsJsonObject("video");
        if (video == null) return;
        registry.notePlaying(str(video, "hint"), str(video, "src"), str(video, "poster"),
                num(video, "dur"), str(video, "title"));
    }

    private void handleFrame(JsonObject obj) {
        JsonObject video = obj.getAsJsonObject("video");
        String data = str(obj, "data");
        if (video == null || TextUtils.isEmpty(data)) return;
        if (!data.startsWith("data:image/")) return;
        registry.noteFrame(str(video, "hint"), str(video, "src"), str(video, "poster"),
                num(video, "dur"), data);
    }

    /** DOM and JSON hits carry no request headers of their own, so synthesise the plausible ones. */
    private Map<String, String> headersFor(String url, boolean originOnlyReferer) {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", userAgent);

        String page = registry.pageUrl();
        if (!TextUtils.isEmpty(page)) {
            String referer = originOnlyReferer ? Http.originOf(page) : page;
            if (!TextUtils.isEmpty(referer)) h.put("Referer", referer);
        }
        try {
            String cookie = CookieManager.getInstance().getCookie(url);
            if (!TextUtils.isEmpty(cookie)) h.put("Cookie", cookie);
        } catch (Exception ignored) {
        }
        return h;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static long num(JsonObject o, String key) {
        JsonElement e = o.get(key);
        try {
            return e == null || e.isJsonNull() ? 0 : e.getAsLong();
        } catch (Exception ex) {
            return 0;
        }
    }

    private static String readAsset(Context context, String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.e(TAG, "Cannot read " + name, e);
            return "";
        }
    }
}
