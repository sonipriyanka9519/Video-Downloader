package com.ms.webview.detect.extract;

import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * ShareChat and Moj.
 *
 * <p>Least certain of the four: ShareChat's web API is not publicly documented and the key
 * names below are inferred from the shapes these apps commonly use, so treat this as the one
 * most likely to need adjusting once you watch a real response. The generic extractor is the
 * safety net if these keys miss.
 */
public class ShareChatExtractor implements SiteExtractor {

    private static final String[] VIDEO_KEYS = {
            "videoUrl", "video_url", "mediaUrl", "media_url", "vUrl", "downloadUrl"
    };
    private static final String[] STREAM_KEYS = {
            "hlsUrl", "hls_url", "streamUrl", "playbackUrl", "adaptiveUrl"
    };
    private static final String[] THUMB_KEYS = {
            "thumbUrl", "thumb_url", "thumbnailUrl", "thumbnail_url", "thumbNailUrl",
            "posterUrl", "coverUrl", "imageUrl"
    };

    @Override
    public boolean appliesTo(String host) {
        return host.contains("sharechat.com") || host.contains("mojapp")
                || host.contains("moj.tv") || host.contains("sharechatcdn.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        String progressive = Json.str(node, VIDEO_KEYS);
        String stream = Json.str(node, STREAM_KEYS);
        if (progressive == null && stream == null) return;

        String hint = hintOf(node);
        String thumbnail = Json.str(node, THUMB_KEYS);
        String title = Json.str(node, "caption", "title", "text", "description");
        String author = authorOf(node);
        long durationMs = Json.num(node, "duration", "videoLength", "durationMs");
        // Some payloads report seconds, some milliseconds. Anything under an hour as a raw
        // number is far more likely to be seconds.
        if (durationMs > 0 && durationMs < 3600) durationMs *= 1000;

        int width = (int) Json.num(node, "width", "videoWidth");
        int height = (int) Json.num(node, "height", "videoHeight");

        if (progressive != null) {
            add(out, progressive, MediaKind.PROGRESSIVE, hint, thumbnail, title, author,
                    durationMs, width, height);
        }
        if (stream != null && !stream.equals(progressive)) {
            add(out, stream, MediaKind.HLS, hint, thumbnail, title, author, durationMs, 0, 0);
        }
    }

    private void add(List<FoundMedia> out, String url, MediaKind kind, String hint,
                     String thumbnail, String title, String author, long durationMs,
                     int width, int height) {
        FoundMedia media = new FoundMedia(url);
        media.kind = url.contains(".m3u8") ? MediaKind.HLS : kind;
        media.groupHint = hint;
        media.thumbnail = thumbnail;
        media.title = title;
        media.author = author;
        media.durationMs = durationMs;
        media.width = width;
        media.height = height;
        if (media.valid()) out.add(media);
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "postId", "post_id", "id", "uuid");
        return id == null ? null : "sc:" + id;
    }

    private String authorOf(JsonObject node) {
        JsonObject user = Json.obj(node, "author", "user", "profile");
        return Json.str(user, "userName", "name", "handle");
    }
}
