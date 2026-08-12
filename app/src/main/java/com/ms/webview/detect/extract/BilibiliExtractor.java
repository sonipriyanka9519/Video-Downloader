package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * Bilibili.
 *
 * <p>Two shapes. The modern {@code dash} payload lists video and audio representations
 * separately, so each video rung is paired with the best audio track and muxed on download.
 * The older {@code durl} payload is a plain list of complete files.
 */
public class BilibiliExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("bilibili.com") || host.contains("bilivideo.com")
                || host.contains("bilibili.tv") || host.contains("biliapi.net");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        String hint = hintOf(node);
        String thumbnail = Json.str(node, "pic", "cover", "first_frame");
        String title = Json.str(node, "title");

        JsonObject dash = Json.obj(node, "dash");
        if (dash != null) {
            fromDash(dash, hint, thumbnail, title, out);
            return;
        }
        fromDurl(node, hint, thumbnail, title, out);
    }

    private void fromDash(JsonObject dash, String hint, String thumbnail, String title,
                          List<FoundMedia> out) {
        long durationMs = Json.seconds(dash, "duration");
        String audioUrl = bestAudio(Json.arr(dash, "audio"));

        JsonArray videos = Json.arr(dash, "video");
        if (videos == null) return;

        for (JsonElement element : videos) {
            if (!element.isJsonObject()) continue;
            JsonObject rendition = element.getAsJsonObject();

            String url = Json.urlFrom(rendition, "baseUrl", "base_url", "backupUrl", "backup_url");
            if (url == null) continue;

            FoundMedia media = new FoundMedia(url);
            // Video-only without its audio companion, so it always needs the mux.
            media.kind = audioUrl != null ? MediaKind.DASH : MediaKind.PROGRESSIVE;
            media.audioUrl = audioUrl;
            media.width = (int) Json.num(rendition, "width");
            media.height = (int) Json.num(rendition, "height");
            media.bitrate = Json.num(rendition, "bandwidth");
            media.thumbnail = thumbnail;
            media.title = title;
            media.durationMs = durationMs;
            media.groupHint = hint;
            if (media.valid()) out.add(media);
        }
    }

    private void fromDurl(JsonObject node, String hint, String thumbnail, String title,
                          List<FoundMedia> out) {
        JsonArray durl = Json.arr(node, "durl");
        if (durl == null) return;

        for (JsonElement element : durl) {
            if (!element.isJsonObject()) continue;
            JsonObject part = element.getAsJsonObject();

            String url = Json.urlFrom(part, "url", "backup_url");
            if (url == null) continue;

            FoundMedia media = new FoundMedia(url);
            media.kind = MediaKind.PROGRESSIVE;
            media.thumbnail = thumbnail;
            media.title = title;
            // "length" here is the segment's own duration, in milliseconds.
            media.durationMs = Json.num(part, "length");
            media.groupHint = hint;
            if (media.valid()) out.add(media);
        }
    }

    private String bestAudio(JsonArray audio) {
        if (audio == null) return null;
        String best = null;
        long bestBandwidth = -1;
        for (JsonElement element : audio) {
            if (!element.isJsonObject()) continue;
            JsonObject track = element.getAsJsonObject();
            long bandwidth = Json.num(track, "bandwidth");
            String url = Json.urlFrom(track, "baseUrl", "base_url");
            if (url != null && bandwidth > bestBandwidth) {
                best = url;
                bestBandwidth = bandwidth;
            }
        }
        return best;
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "bvid", "aid", "cid", "id");
        return id == null ? null : "bili:" + id;
    }
}
