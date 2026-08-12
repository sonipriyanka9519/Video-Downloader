package com.ms.webview.detect.extract;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Kwai / Kuaishou.
 *
 * <p>Kwai lists its CDN mirrors rather than its qualities: {@code mainMvUrls} is several
 * addresses for the same rendition, so only the first is taken — the rest would show up as
 * duplicate entries of an identical file. Where a real ladder exists it is under
 * {@code manifest}, as representations carrying a height each.
 */
public class KwaiExtractor implements SiteExtractor {

    private static final String[] URL_KEYS = {
            "mainMvUrls", "photoUrl", "srcNoMark", "playUrl", "mainMvUrl", "h265Urls"
    };
    private static final String[] COVER_KEYS = {
            "coverUrls", "coverUrl", "webpCoverUrls", "animatedCoverUrl", "thumbnailUrl"
    };
    private static final String[] TITLE_KEYS = {"caption", "title", "description"};

    @Override
    public boolean appliesTo(String host) {
        return host.contains("kwai.com") || host.contains("kuaishou.com")
                || host.contains("kwimgs.com") || host.contains("kwaicdn")
                || host.contains("gifshow.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        Meta meta = new Meta(node);

        // The adaptive ladder, when the payload carries one.
        JsonObject manifest = Json.obj(node, "manifest", "adaptationSet");
        JsonArray representations = manifest == null
                ? null : Json.arr(manifest, "representation", "representations", "adaptationSet");
        if (representations != null) {
            for (JsonElement element : representations) {
                if (!element.isJsonObject()) continue;
                JsonObject rep = element.getAsJsonObject();
                add(out, Json.urlFrom(rep, "url", "backupUrl"),
                        (int) Json.num(rep, "height"), meta);
            }
        }

        // The plain address. First mirror only: the others are the same bytes elsewhere.
        add(out, Json.urlFrom(node, URL_KEYS),
                (int) Json.num(node, "height", "videoHeight"), meta);
    }

    private void add(List<FoundMedia> out, @Nullable String url, int height, Meta meta) {
        if (url == null || !url.startsWith("http")) return;

        FoundMedia media = new FoundMedia(url);
        media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
        media.height = height;
        media.thumbnail = meta.thumbnail;
        media.title = meta.title;
        media.author = meta.author;
        media.durationMs = meta.durationMs;
        media.groupHint = meta.hint;
        if (media.valid()) out.add(media);
    }

    private static class Meta {
        final String hint;
        final String title;
        final String author;
        final String thumbnail;
        final long durationMs;

        Meta(JsonObject node) {
            String id = Json.str(node, "photoId", "photoID", "id");
            hint = id == null ? null : "kwai:" + id;
            title = Json.str(node, TITLE_KEYS);
            thumbnail = Json.urlFrom(node, COVER_KEYS);
            author = Json.str(Json.obj(node, "user", "author"),
                    "userName", "user_name", "name", "nickName");

            // Kwai states milliseconds, but not always — a value under a second is seconds.
            long raw = Json.num(node, "duration", "durationMs", "videoDuration");
            durationMs = raw <= 0 ? 0 : (raw < 1000 ? raw * 1000 : raw);
        }
    }
}
