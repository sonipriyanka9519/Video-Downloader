package com.ms.webview.detect.extract;

import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Tumblr.
 *
 * <p>Covers both post formats: the newer content blocks, where a video block carries a
 * {@code media} object and a poster list, and the legacy {@code video_url} field.
 */
public class TumblrExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("tumblr.com") || host.contains("tumblr.co");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        String hint = hintOf(node);
        String title = Json.str(node, "summary", "caption", "title");

        // New post format: a typed content block.
        if ("video".equalsIgnoreCase(String.valueOf(Json.str(node, "type")))) {
            JsonObject media = Json.obj(node, "media");
            String url = media != null ? Json.str(media, "url") : Json.str(node, "url");
            if (url != null && Json.looksLikeMediaUrl(url)) {
                FoundMedia found = new FoundMedia(url);
                found.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
                found.width = (int) Json.num(media == null ? node : media, "width");
                found.height = (int) Json.num(media == null ? node : media, "height");
                found.thumbnail = Json.str(Json.first(Json.arr(node, "poster")), "url");
                found.title = title;
                found.groupHint = hint;
                if (found.valid()) out.add(found);
                return;
            }
        }

        String legacy = Json.str(node, "video_url");
        if (legacy == null) return;

        FoundMedia found = new FoundMedia(legacy);
        found.kind = legacy.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
        found.thumbnail = Json.str(node, "thumbnail_url", "image_permalink");
        found.width = (int) Json.num(node, "thumbnail_width");
        found.height = (int) Json.num(node, "thumbnail_height");
        found.durationMs = Json.seconds(node, "duration");
        found.title = title;
        found.groupHint = hint;
        if (found.valid()) out.add(found);
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "id_string", "id", "post_url");
        return id == null ? null : "tmb:" + id;
    }
}
