package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Vimeo.
 *
 * <p>Matched on the player config root rather than on the file list, because the title, poster
 * and duration sit under {@code video} while the renditions sit under {@code request.files} —
 * two separate subtrees the walker would otherwise visit in isolation, losing the pairing.
 *
 * <p>{@code files.progressive} is an explicit ladder: one entry per rendition with its own
 * width, height and quality label.
 */
public class VimeoExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("vimeo.com") || host.contains("vimeocdn.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonObject request = Json.obj(node, "request");
        JsonObject video = Json.obj(node, "video");
        if (request == null || video == null) return;

        JsonObject files = Json.obj(request, "files");
        if (files == null) return;

        String hint = hintOf(video);
        String thumbnail = thumbnailOf(video);
        String title = Json.str(video, "title");
        String author = Json.str(Json.obj(video, "owner"), "name");
        long durationMs = Json.seconds(video, "duration");

        JsonArray progressive = Json.arr(files, "progressive");
        if (progressive != null) {
            for (JsonElement element : progressive) {
                if (!element.isJsonObject()) continue;
                JsonObject rendition = element.getAsJsonObject();
                String url = Json.str(rendition, "url");
                if (url == null) continue;

                FoundMedia media = new FoundMedia(url);
                media.kind = MediaKind.PROGRESSIVE;
                media.width = (int) Json.num(rendition, "width");
                media.height = (int) Json.num(rendition, "height");
                media.thumbnail = thumbnail;
                media.title = title;
                media.author = author;
                media.durationMs = durationMs;
                media.groupHint = hint;
                if (media.valid()) out.add(media);
            }
        }

        // The adaptive entries are keyed by CDN name, so take whichever is listed.
        addAdaptive(Json.obj(files, "hls"), MediaKind.HLS, hint, thumbnail, title, author,
                durationMs, out);
        addAdaptive(Json.obj(files, "dash"), MediaKind.DASH, hint, thumbnail, title, author,
                durationMs, out);
    }

    private void addAdaptive(JsonObject group, MediaKind kind, String hint, String thumbnail,
                             String title, String author, long durationMs, List<FoundMedia> out) {
        if (group == null) return;
        JsonObject cdns = Json.obj(group, "cdns");
        if (cdns == null) return;

        for (String name : cdns.keySet()) {
            JsonElement element = cdns.get(name);
            if (element == null || !element.isJsonObject()) continue;
            String url = Json.str(element.getAsJsonObject(), "url", "avc_url");
            if (url == null) continue;

            FoundMedia media = new FoundMedia(url);
            media.kind = kind;
            media.thumbnail = thumbnail;
            media.title = title;
            media.author = author;
            media.durationMs = durationMs;
            media.groupHint = hint;
            if (media.valid()) out.add(media);
            // One CDN is enough; they serve identical content.
            return;
        }
    }

    private String thumbnailOf(JsonObject video) {
        JsonObject thumbs = Json.obj(video, "thumbs");
        String keyed = Json.largestKeyedUrl(thumbs);
        if (keyed != null) return keyed;
        return Json.str(video, "thumbnail_url", "poster");
    }

    private String hintOf(JsonObject video) {
        String id = Json.str(video, "id", "video_id");
        return id == null ? null : "vimeo:" + id;
    }
}
