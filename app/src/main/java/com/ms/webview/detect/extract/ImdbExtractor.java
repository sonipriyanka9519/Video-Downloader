package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;
import java.util.Locale;

/**
 * IMDb trailers and clips.
 *
 * <p>{@code playbackURLs} names each rendition with a definition rather than a pixel height —
 * "SD", "480p", "1080p" — so the label is translated back into a height for the ladder.
 */
public class ImdbExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("imdb.com") || host.contains("media-amazon.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonArray playback = Json.arr(node, "playbackURLs");
        if (playback == null) return;

        String hint = hintOf(node);
        String thumbnail = thumbnailOf(node);
        String title = titleOf(node);
        long durationMs = Json.num(node, "runtime", "durationMs");
        if (durationMs == 0) durationMs = Json.seconds(node, "runtimeSeconds", "duration");

        for (JsonElement element : playback) {
            if (!element.isJsonObject()) continue;
            JsonObject rendition = element.getAsJsonObject();

            String url = Json.str(rendition, "url");
            if (url == null) continue;

            String mime = Json.str(rendition, "videoMimeType", "mimeType");
            FoundMedia media = new FoundMedia(url);
            media.kind = isHls(url, mime) ? MediaKind.HLS : MediaKind.PROGRESSIVE;
            media.height = heightOf(Json.str(rendition, "videoDefinition", "definition"));
            media.thumbnail = thumbnail;
            media.title = title;
            media.durationMs = durationMs;
            media.groupHint = hint;
            if (media.valid()) out.add(media);
        }
    }

    private static boolean isHls(String url, String mime) {
        if (url.contains(".m3u8")) return true;
        return mime != null && mime.toUpperCase(Locale.US).contains("M3U8");
    }

    /** "1080p" carries its height; "SD" and "HD" do not, so they are mapped by convention. */
    private static int heightOf(String definition) {
        if (definition == null) return 0;
        String d = definition.trim().toUpperCase(Locale.US);
        if (d.equals("SD")) return 480;
        if (d.equals("HD")) return 720;
        if (d.equals("UHD") || d.equals("4K")) return 2160;
        try {
            return Integer.parseInt(d.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String thumbnailOf(JsonObject node) {
        String url = Json.str(Json.obj(node, "thumbnail", "primaryImage"), "url");
        if (url != null) return url;
        return Json.str(node, "thumbnailUrl", "previewURL");
    }

    private String titleOf(JsonObject node) {
        String direct = Json.str(node, "title", "name");
        if (direct != null) return direct;
        return Json.str(Json.obj(node, "titleText"), "text");
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "id", "videoId", "viConst");
        return id == null ? null : "imdb:" + id;
    }
}
