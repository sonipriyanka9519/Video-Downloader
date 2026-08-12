package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Instagram reels and video posts.
 *
 * <p>Two shapes matter. The current app API returns a media node carrying a
 * {@code video_versions} array — one entry per encoded quality, which maps directly onto the
 * quality picker. Older and embed responses carry a single {@code video_url} instead.
 */
public class InstagramExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        // Threads used to be handled here, since it serves the identical shape. It has its own
        // file now — see ThreadsExtractor — so that a change to one cannot break the other.
        return host.contains("instagram.com") || host.contains("cdninstagram.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        String hint = hintOf(node);
        String thumbnail = thumbnailOf(node);
        String title = captionOf(node);
        long durationMs = Json.seconds(node, "video_duration");
        if (durationMs == 0) durationMs = Json.num(node, "video_duration_ms");

        // The DASH manifest holds every rendition Instagram has, where video_versions often
        // carries a single encode. When it is present it is the better source of qualities.
        String manifest = Json.str(node, "video_dash_manifest", "dash_manifest");
        if (manifest != null && manifest.contains("<MPD")) {
            FoundMedia media = new FoundMedia(manifestPlaceholder(hint));
            media.kind = MediaKind.DASH;
            media.inlineManifest = manifest;
            media.thumbnail = thumbnail;
            media.title = title;
            media.author = authorOf(node);
            media.durationMs = durationMs;
            media.groupHint = hint;
            out.add(media);
        }

        JsonArray versions = Json.arr(node, "video_versions");
        if (versions != null) {
            for (JsonElement element : versions) {
                if (!element.isJsonObject()) continue;
                JsonObject version = element.getAsJsonObject();
                String url = Json.str(version, "url");
                if (url == null) continue;

                FoundMedia media = new FoundMedia(url);
                media.kind = MediaKind.PROGRESSIVE;
                media.width = (int) Json.num(version, "width");
                media.height = (int) Json.num(version, "height");
                media.thumbnail = thumbnail;
                media.title = title;
                media.author = authorOf(node);
                media.durationMs = durationMs;
                media.groupHint = hint;
                if (media.valid()) out.add(media);
            }
            return;
        }

        // Single-URL shape, used by embeds and the older GraphQL responses.
        String single = Json.str(node, "video_url");
        if (single != null) {
            FoundMedia media = new FoundMedia(single);
            media.thumbnail = thumbnail;
            media.title = title;
            media.author = authorOf(node);
            media.durationMs = durationMs;
            media.groupHint = hint;

            JsonObject dimensions = Json.obj(node, "dimensions");
            if (dimensions != null) {
                media.width = (int) Json.num(dimensions, "width");
                media.height = (int) Json.num(dimensions, "height");
            }
            if (media.valid()) out.add(media);
        }
    }

    /**
     * An inline manifest has no URL of its own, but every FoundMedia needs one to be keyed by.
     * The hint is unique per post, which is exactly the grouping we want.
     */
    private static String manifestPlaceholder(String hint) {
        return "https://dash.local/" + (hint == null ? "manifest" : hint.replace(':', '_'));
    }

    private String hintOf(JsonObject node) {
        String hint = Json.str(node, "code", "shortcode", "pk", "id");
        return hint == null ? null : "ig:" + hint;
    }

    private String thumbnailOf(JsonObject node) {
        JsonObject versions = Json.obj(node, "image_versions2");
        if (versions != null) {
            JsonObject candidate = Json.first(Json.arr(versions, "candidates"));
            String url = Json.str(candidate, "url");
            if (url != null) return url;
        }
        return Json.str(node, "display_url", "thumbnail_src", "thumbnail_url", "cover_frame_url");
    }

    private String captionOf(JsonObject node) {
        JsonObject caption = Json.obj(node, "caption");
        String text = Json.str(caption, "text");
        if (text != null) return trim(text);

        // edge_media_to_caption.edges[0].node.text
        JsonObject edge = Json.obj(node, "edge_media_to_caption");
        JsonObject firstEdge = Json.first(Json.arr(edge, "edges"));
        String edgeText = Json.str(Json.obj(firstEdge, "node"), "text");
        return edgeText == null ? null : trim(edgeText);
    }

    private String authorOf(JsonObject node) {
        JsonObject user = Json.obj(node, "user", "owner");
        return Json.str(user, "username", "full_name");
    }

    private static String trim(String text) {
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > 90 ? single.substring(0, 90) + "…" : single;
    }
}
