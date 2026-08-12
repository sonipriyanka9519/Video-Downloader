package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * X / Twitter.
 *
 * <p>The {@code video_info} node is stable and generous: it lists every bitrate as a separate
 * MP4 variant plus an HLS master, and the poster sits next to it as {@code media_url_https}.
 */
public class TwitterExtractor implements SiteExtractor {

    private static final Pattern STATUS_ID = Pattern.compile("/status/(\\d+)");

    @Override
    public boolean appliesTo(String host) {
        return host.contains("twitter.com") || host.contains("x.com")
                || host.contains("twimg.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonObject info = Json.obj(node, "video_info");
        if (info == null) return;

        JsonArray variants = Json.arr(info, "variants");
        if (variants == null) return;

        // The poster and id live on the media node that owns video_info, not inside it. The key
        // varies between the GraphQL payload, the v1.1 entities and the v2 media object.
        String thumbnail = Json.str(node,
                "media_url_https", "media_url", "preview_image_url", "poster_image",
                "thumbnail_url", "expanded_media_url");
        if (thumbnail == null) {
            thumbnail = Json.str(Json.obj(node, "additional_media_info"), "preview_image_url");
        }
        String hint = hintOf(node);
        long durationMs = Json.num(info, "duration_millis");

        for (JsonElement element : variants) {
            if (!element.isJsonObject()) continue;
            JsonObject variant = element.getAsJsonObject();
            String url = Json.str(variant, "url");
            if (url == null) continue;

            String contentType = Json.str(variant, "content_type");
            FoundMedia media = new FoundMedia(url);
            media.kind = contentType != null && contentType.contains("mpegURL")
                    ? MediaKind.HLS : MediaKind.PROGRESSIVE;
            media.bitrate = Json.num(variant, "bitrate");
            media.thumbnail = thumbnail;
            media.durationMs = durationMs;
            media.groupHint = hint;

            // Twitter puts the resolution in the path: /vid/avc1/1280x720/....mp4
            // When it does not, the bitrate drives the ordering instead.
            int[] size = sizeFromPath(url);
            if (size != null) {
                media.width = size[0];
                media.height = size[1];
            }
            if (media.valid()) out.add(media);
        }
    }

    /**
     * The tweet id, taken from the media entity's {@code expanded_url}.
     *
     * <p>It has to be the tweet id rather than the media key, because the page scanner derives
     * the same hint from the address bar — {@code /user/status/<id>} — and the two must agree
     * for a frame captured off the playing element to find its way to the right card. A media
     * key would never match, which is why X was the one platform whose previews never arrived.
     */
    @Nullable
    private static String hintOf(JsonObject node) {
        String expanded = Json.str(node, "expanded_url", "url", "display_url");
        if (expanded != null) {
            Matcher m = STATUS_ID.matcher(expanded);
            if (m.find()) return "tw:" + m.group(1);
        }
        String key = Json.str(node, "media_key", "id_str");
        return key == null ? null : "tw:" + key;
    }

    private static int[] sizeFromPath(String url) {
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("/(\\d{2,4})x(\\d{2,4})/").matcher(url);
        if (!m.find()) return null;
        try {
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (Exception e) {
            return null;
        }
    }
}
