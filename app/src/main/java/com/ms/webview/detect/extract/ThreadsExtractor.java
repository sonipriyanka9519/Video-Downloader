package com.ms.webview.detect.extract;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Threads.
 *
 * <p>Threads is built on Instagram's backend and serves the same node shape — {@code
 * video_versions} for the ladder, {@code image_versions2} for the poster. It gets its own file
 * anyway, because it is its own product and drifts on its own schedule: when Threads changes,
 * the fix belongs here and should not risk Instagram.
 *
 * <p>A thread can carry several videos in one post, and each keeps its own card. The grouping
 * key is the media id, so the qualities of one clip collapse together while separate clips stay
 * separate.
 */
public class ThreadsExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("threads.net") || host.contains("threads.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonArray versions = Json.arr(node, "video_versions", "videoVersions");
        String manifest = Json.str(node, "video_dash_manifest", "dash_manifest");
        boolean hasManifest = manifest != null && manifest.contains("<MPD");
        if (versions == null && !hasManifest) return;

        String hint = hintOf(node);
        String thumbnail = thumbnailOf(node);
        String title = titleOf(node);
        String author = Json.str(Json.obj(node, "user", "owner"), "username", "full_name");
        long durationMs = Json.seconds(node, "video_duration");

        // The manifest carries the full ladder in one go, so it is preferred where present.
        if (hasManifest) {
            FoundMedia media = new FoundMedia(
                    "https://dash.local/" + (hint == null ? "th" : hint.replace(':', '_')));
            media.kind = MediaKind.DASH;
            media.inlineManifest = manifest;
            media.thumbnail = thumbnail;
            media.title = title;
            media.author = author;
            media.durationMs = durationMs;
            media.groupHint = hint;
            out.add(media);
        }

        if (versions == null) return;
        for (JsonElement element : versions) {
            if (!element.isJsonObject()) continue;
            JsonObject version = element.getAsJsonObject();

            String url = Json.str(version, "url");
            if (url == null || !url.startsWith("http")) continue;

            FoundMedia media = new FoundMedia(url);
            media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
            media.width = (int) Json.num(version, "width");
            media.height = (int) Json.num(version, "height");
            media.thumbnail = thumbnail;
            media.title = title;
            media.author = author;
            media.durationMs = durationMs;
            media.groupHint = hint;
            if (media.valid()) out.add(media);
        }
    }

    @Nullable
    private static String thumbnailOf(JsonObject node) {
        JsonObject images = Json.obj(node, "image_versions2");
        JsonObject candidate = Json.first(Json.arr(images, "candidates"));
        String url = Json.str(candidate, "url");
        if (url != null) return url;
        return Json.str(node, "thumbnail_url", "display_url", "thumbnail_src");
    }

    @Nullable
    private static String titleOf(JsonObject node) {
        String caption = Json.str(Json.obj(node, "caption"), "text");
        return caption != null ? caption : Json.str(node, "accessibility_caption", "title");
    }

    @Nullable
    private static String hintOf(JsonObject node) {
        String id = Json.str(node, "id", "pk", "code", "media_id");
        return id == null ? null : "th:" + id;
    }
}
