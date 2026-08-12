package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import androidx.annotation.Nullable;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.List;

/**
 * Twitch clips.
 *
 * <p>{@code videoQualities} is a straightforward ladder of complete files, but each address is
 * only half of one: the CDN refuses it until the signature and token from
 * {@code playbackAccessToken} are appended. Both live in the same response, so signing happens
 * here and the rest of the app never has to know.
 *
 * <p>Clips only. A VOD or a live stream is delivered as a playlist signed per session, which is
 * a different exercise, and a live stream has no end to download to in any case.
 */
public class TwitchExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("twitch.tv") || host.contains("ttvnw.net")
                || host.contains("jtvnw.net");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonArray qualities = Json.arr(node, "videoQualities");
        if (qualities == null) return;

        String hint = hintOf(node);
        String thumbnail = Json.str(node, "thumbnailURL", "thumbnail_url", "previewThumbnailURL");
        String title = Json.str(node, "title");
        String author = authorOf(node);
        long durationMs = Json.seconds(node, "durationSeconds", "duration");

        // The access token sits beside the ladder, and the CDN refuses a sourceURL without it.
        JsonObject token = Json.obj(node, "playbackAccessToken", "videoPlaybackAccessToken");
        String signature = Json.str(token, "signature");
        String value = Json.str(token, "value");

        for (JsonElement element : qualities) {
            if (!element.isJsonObject()) continue;
            JsonObject rendition = element.getAsJsonObject();

            String url = Json.str(rendition, "sourceURL", "source_url", "url");
            if (url == null) continue;

            FoundMedia media = new FoundMedia(sign(url, signature, value));
            media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
            // "quality" is the height as a bare string: "1080", "720".
            media.height = (int) parse(Json.str(rendition, "quality"));
            media.thumbnail = thumbnail;
            media.title = title;
            media.author = author;
            media.durationMs = durationMs;
            media.groupHint = hint;
            if (media.valid()) out.add(media);
        }
    }

    /**
     * Appends the playback access token.
     *
     * <p>A clip's {@code sourceURL} is only the base address; the CDN answers 403 until the
     * signature and token from the same response are attached. Detection therefore looked fine
     * while every download and every preview failed.
     */
    private static String sign(String url, @Nullable String signature, @Nullable String value) {
        if (signature == null || value == null) return url;
        try {
            return url + (url.contains("?") ? "&" : "?")
                    + "sig=" + signature
                    + "&token=" + URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return url;
        }
    }

    private static long parse(String value) {
        if (value == null) return 0;
        try {
            return Long.parseLong(value.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String authorOf(JsonObject node) {
        JsonObject broadcaster = Json.obj(node, "broadcaster", "curator");
        return Json.str(broadcaster, "displayName", "login", "name");
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "slug", "id");
        return id == null ? null : "twitch:" + id;
    }
}
