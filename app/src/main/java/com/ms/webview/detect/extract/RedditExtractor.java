package com.ms.webview.detect.extract;

import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Reddit's hosted video.
 *
 * <p>Only the manifests are offered. {@code fallback_url} looks like the obvious choice and is
 * the one most downloaders take, but on any post with sound it is the <em>video-only</em>
 * rendition — Reddit keeps the audio in a separate track — so downloading it yields a silent
 * file. The DASH and HLS manifests name both tracks, and both of our manifest paths mux them.
 */
public class RedditExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("reddit.com") || host.contains("redd.it")
                || host.contains("redditmedia.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonObject video = redditVideo(node);
        if (video == null) return;

        String hint = hintOf(node);
        String thumbnail = thumbnailOf(node);
        String title = Json.str(node, "title");
        long durationMs = Json.seconds(video, "duration");
        int width = (int) Json.num(video, "width");
        int height = (int) Json.num(video, "height");

        add(out, Json.str(video, "dash_url"), MediaKind.DASH,
                hint, thumbnail, title, durationMs, width, height);
        add(out, Json.str(video, "hls_url"), MediaKind.HLS,
                hint, thumbnail, title, durationMs, width, height);

        // A GIF-style post has no audio track at all, so its single file is safe to offer.
        boolean silent = "true".equalsIgnoreCase(String.valueOf(Json.str(video, "is_gif")));
        if (silent) {
            add(out, Json.str(video, "fallback_url"), MediaKind.PROGRESSIVE,
                    hint, thumbnail, title, durationMs, width, height);
        }
    }

    private void add(List<FoundMedia> out, String url, MediaKind kind, String hint,
                     String thumbnail, String title, long durationMs, int width, int height) {
        if (url == null) return;
        FoundMedia media = new FoundMedia(url);
        media.kind = kind;
        media.groupHint = hint;
        media.thumbnail = thumbnail;
        media.title = title;
        media.durationMs = durationMs;
        media.width = width;
        media.height = height;
        if (media.valid()) out.add(media);
    }

    private JsonObject redditVideo(JsonObject node) {
        JsonObject direct = Json.obj(node, "reddit_video", "reddit_video_preview");
        if (direct != null) return direct;

        JsonObject container = Json.obj(node, "secure_media", "media");
        return container == null ? null : Json.obj(container, "reddit_video");
    }

    private String thumbnailOf(JsonObject node) {
        String thumb = Json.str(node, "thumbnail");
        // Reddit uses the words "self", "default" and "nsfw" in place of a URL.
        if (thumb != null && thumb.startsWith("http")) return thumb;
        return Json.str(node, "url_overridden_by_dest", "preview_url");
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "id", "name", "permalink");
        return id == null ? null : "rd:" + id;
    }
}
