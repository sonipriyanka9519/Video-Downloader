package com.ms.webview.detect.extract;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flickr video.
 *
 * <p>Flickr keys its ladder by name rather than listing it: {@code streams} is a map of
 * {@code 700}, {@code 1080p}, {@code iphone_wifi} and friends onto addresses. The key is the
 * only statement of quality there is, so it is what the rung is read from.
 *
 * <p>Photos vastly outnumber videos on Flickr and travel through the same {@code sizes} shape,
 * so an address has to look like media before it is offered.
 */
public class FlickrExtractor implements SiteExtractor {

    private static final String[] STREAM_KEYS = {"streams", "sizes", "video_streams"};

    @Override
    public boolean appliesTo(String host) {
        return host.contains("flickr.com") || host.contains("staticflickr.com")
                || host.contains("flickr.net");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonObject video = Json.obj(node, "video", "videoModel");
        JsonObject source = video != null ? video : node;

        JsonObject streams = Json.obj(source, STREAM_KEYS);
        if (streams == null) return;

        String hint = hintOf(node, source);
        String title = Json.str(node, "title", "name", "description");
        String thumbnail = Json.urlFrom(node, "poster", "thumbnailUrl", "displayUrl", "url_l");
        long durationMs = Json.seconds(source, "duration", "video_duration");

        for (Map.Entry<String, JsonElement> entry : streams.entrySet()) {
            String url = urlOf(entry.getValue());
            if (url == null || !Json.looksLikeMediaUrl(url)) continue;

            FoundMedia media = new FoundMedia(url);
            media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
            media.height = heightOf(entry.getKey());
            media.title = title;
            media.thumbnail = thumbnail;
            media.durationMs = durationMs;
            media.groupHint = hint;
            if (media.valid()) out.add(media);
        }
    }

    /** A stream is either the address itself or an object wrapping it. */
    @Nullable
    private static String urlOf(JsonElement element) {
        if (element == null) return null;
        if (element.isJsonPrimitive()) {
            String s = element.getAsString();
            return s != null && s.startsWith("http") ? s : null;
        }
        if (element.isJsonObject()) {
            return Json.urlFrom(element.getAsJsonObject(), "url", "src", "source", "_url");
        }
        return null;
    }

    /**
     * "1080p" is a height; "700" is Flickr's own size ladder and happens to be a width; the
     * named tiers are neither. Ordering them roughly beats leaving every rung at zero.
     */
    private static int heightOf(String key) {
        if (key == null) return 0;
        String k = key.toLowerCase(Locale.US);
        if (k.startsWith("iphone")) return 360;
        if (k.equals("mobile")) return 288;
        if (k.equals("orig") || k.equals("original")) return 1080;
        try {
            return Integer.parseInt(k.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Nullable
    private static String hintOf(JsonObject node, JsonObject source) {
        String id = Json.str(node, "id", "photoId", "_id");
        if (id == null) id = Json.str(source, "id", "photoId");
        return id == null ? null : "flickr:" + id;
    }
}
