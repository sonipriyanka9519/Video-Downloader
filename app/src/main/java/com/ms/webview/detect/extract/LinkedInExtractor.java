package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * LinkedIn native video.
 *
 * <p>{@code progressiveStreams} is the ladder, each rung listing its own dimensions and byte
 * count — an unusually complete description, so these cards get exact sizes rather than
 * estimates. The adaptive master is offered alongside.
 */
public class LinkedInExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("linkedin.com") || host.contains("licdn.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonArray progressive = Json.arr(node, "progressiveStreams");
        JsonArray adaptive = Json.arr(node, "adaptiveStreams");
        if (progressive == null && adaptive == null) return;

        String hint = hintOf(node);
        String thumbnail = thumbnailOf(node);
        String title = Json.str(node, "title", "text");
        long durationMs = Json.num(node, "duration", "durationMs");

        if (progressive != null) {
            for (JsonElement element : progressive) {
                if (!element.isJsonObject()) continue;
                JsonObject stream = element.getAsJsonObject();

                String url = locationOf(stream);
                if (url == null) continue;

                FoundMedia media = new FoundMedia(url);
                media.kind = MediaKind.PROGRESSIVE;
                media.width = (int) Json.num(stream, "width");
                media.height = (int) Json.num(stream, "height");
                media.bitrate = Json.num(stream, "bitRate", "bitrate");
                media.thumbnail = thumbnail;
                media.title = title;
                media.durationMs = durationMs;
                media.groupHint = hint;
                if (media.valid()) out.add(media);
            }
        }

        if (adaptive != null) {
            for (JsonElement element : adaptive) {
                if (!element.isJsonObject()) continue;
                String url = Json.str(element.getAsJsonObject(),
                        "masterPlaylistUrl", "initializationUrl");
                if (url == null) continue;

                FoundMedia media = new FoundMedia(url);
                media.kind = url.contains(".mpd") ? MediaKind.DASH : MediaKind.HLS;
                media.thumbnail = thumbnail;
                media.title = title;
                media.durationMs = durationMs;
                media.groupHint = hint;
                if (media.valid()) out.add(media);
            }
        }
    }

    /** The URL sits one level down, in a list of CDN locations. */
    private String locationOf(JsonObject stream) {
        String direct = Json.str(stream, "url", "streamingUrl");
        if (direct != null) return direct;

        JsonObject location = Json.first(Json.arr(stream, "streamingLocations"));
        return Json.str(location, "url");
    }

    private String thumbnailOf(JsonObject node) {
        String url = Json.str(node, "thumbnail", "thumbnailUrl", "posterFrameUrl");
        if (url != null) return url;
        return Json.str(Json.obj(node, "thumbnail", "posterFrame"), "url");
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "entityUrn", "urn", "id", "trackingId");
        return id == null ? null : "li:" + id;
    }
}
