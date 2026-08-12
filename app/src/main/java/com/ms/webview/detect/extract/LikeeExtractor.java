package com.ms.webview.detect.extract;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Likee.
 *
 * <p>One rendition per post and no ladder — Likee serves whatever it transcoded and nothing
 * else, so a card here will always have a single quality. The watermark-free address is
 * preferred when the payload offers one, since it is the same video without the overlay.
 */
public class LikeeExtractor implements SiteExtractor {

    /** Watermark-free first: same content, and it is what the user actually wants saved. */
    private static final String[] URL_KEYS = {
            "videoUrlNoWatermark", "video_url_no_watermark",
            "videoUrl", "video_url", "resourceUrl", "playUrl"
    };
    private static final String[] COVER_KEYS = {
            "coverUrl", "cover_url", "thumbUrl", "imageUrl", "picUrl", "firstFrameUrl"
    };

    @Override
    public boolean appliesTo(String host) {
        return host.contains("likee.video") || host.contains("like.video")
                || host.contains("likeevideo") || host.contains("likee.com")
                || host.contains("bigo.sg");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        String url = Json.urlFrom(node, URL_KEYS);
        if (url == null || !url.startsWith("http")) return;

        FoundMedia media = new FoundMedia(url);
        media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
        media.thumbnail = Json.urlFrom(node, COVER_KEYS);
        media.title = Json.str(node, "msgText", "title", "description", "caption");
        media.author = Json.str(Json.obj(node, "user", "author", "poster"),
                "nickName", "userName", "name");
        media.width = (int) Json.num(node, "videoWidth", "width");
        media.height = (int) Json.num(node, "videoHeight", "height");
        media.durationMs = durationOf(node);
        media.groupHint = hintOf(node);

        if (media.valid()) out.add(media);
    }

    /** Likee states seconds. */
    private static long durationOf(JsonObject node) {
        long raw = Json.num(node, "videoDuration", "duration");
        if (raw <= 0) return 0;
        return raw < 1000 ? raw * 1000 : raw;
    }

    @Nullable
    private static String hintOf(JsonObject node) {
        String id = Json.str(node, "postId", "post_id", "videoId", "id");
        return id == null ? null : "likee:" + id;
    }
}
