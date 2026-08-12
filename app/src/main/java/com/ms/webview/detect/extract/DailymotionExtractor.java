package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Dailymotion.
 *
 * <p>The player metadata response carries a {@code qualities} map keyed by height — "144",
 * "480", "1080", plus an "auto" entry holding the HLS master. Each key maps to a list of
 * {@code {type, url}} entries, so the ladder is stated outright and needs no inference.
 */
public class DailymotionExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("dailymotion.com") || host.contains("dmcdn.net")
                || host.contains("dai.ly");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonObject qualities = Json.obj(node, "qualities");
        if (qualities == null) return;

        String hint = hintOf(node);
        String thumbnail = thumbnailOf(node);
        String title = Json.str(node, "title");
        String author = authorOf(node);
        long durationMs = Json.seconds(node, "duration");

        for (String key : qualities.keySet()) {
            JsonElement entry = qualities.get(key);
            if (entry == null || !entry.isJsonArray()) continue;

            // "auto" is the adaptive master; everything else names its height directly.
            int height = "auto".equalsIgnoreCase(key) ? 0 : digits(key);

            for (JsonElement element : (JsonArray) entry) {
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();
                String url = Json.str(source, "url");
                if (url == null) continue;

                FoundMedia media = new FoundMedia(url);
                media.kind = kindOf(url, Json.str(source, "type"));
                media.height = height;
                media.thumbnail = thumbnail;
                media.title = title;
                media.author = author;
                media.durationMs = durationMs;
                media.groupHint = hint;
                if (media.valid()) out.add(media);
            }
        }
    }

    private static MediaKind kindOf(String url, String type) {
        String lower = url.toLowerCase(java.util.Locale.US);
        if (lower.contains(".m3u8")) return MediaKind.HLS;
        if (lower.contains(".mpd")) return MediaKind.DASH;
        if (type != null && type.toLowerCase(java.util.Locale.US).contains("mpegurl")) {
            return MediaKind.HLS;
        }
        return MediaKind.PROGRESSIVE;
    }

    private String thumbnailOf(JsonObject node) {
        String keyed = Json.largestKeyedUrl(Json.obj(node, "thumbnails"));
        if (keyed != null) return keyed;
        return Json.str(node, "poster_url", "thumbnail_url", "thumbnail_1080_url",
                "thumbnail_720_url", "thumbnail_480_url");
    }

    private String authorOf(JsonObject node) {
        JsonObject owner = Json.obj(node, "owner");
        return Json.str(owner, "screenname", "username", "name");
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "id", "xid", "video_id");
        return id == null ? null : "dm:" + id;
    }

    private static int digits(String key) {
        try {
            return Integer.parseInt(key.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
