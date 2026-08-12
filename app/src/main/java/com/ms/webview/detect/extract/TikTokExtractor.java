package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;

/**
 * TikTok.
 *
 * <p>The {@code bit_rate} / {@code bitrateInfo} array is the quality ladder: one entry per gear,
 * each with its own address, dimensions and byte count. {@code play_addr} is the default rung
 * and is used on its own when the ladder is absent.
 *
 * <p>TikTok spells everything two ways — snake_case in the app API, PascalCase in the web
 * payload — so both are checked at every step.
 */
public class TikTokExtractor implements SiteExtractor {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("tiktok.com") || host.contains("tiktokcdn")
                || host.contains("tiktokv.com") || host.contains("byteoversea");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        boolean isVideoNode = node.has("play_addr") || node.has("playAddr")
                || node.has("bit_rate") || node.has("bitrateInfo") || node.has("bitrate_info");
        if (!isVideoNode) return;

        String hint = hintOf(node);
        String thumbnail = Json.urlFrom(node, "cover", "originCover", "origin_cover",
                "dynamicCover", "dynamic_cover", "reflowCover");
        long durationMs = durationOf(node);

        boolean addedLadder = false;
        JsonArray ladder = Json.arr(node, "bit_rate", "bitrateInfo", "bitrate_info");
        if (ladder != null) {
            for (JsonElement element : ladder) {
                if (!element.isJsonObject()) continue;
                JsonObject gear = element.getAsJsonObject();

                JsonObject address = Json.obj(gear, "play_addr", "PlayAddr", "playAddr");
                String url = address != null
                        ? Json.urlFrom(gear, "play_addr", "PlayAddr", "playAddr")
                        : null;
                if (url == null) continue;

                FoundMedia media = base(url, hint, thumbnail, durationMs);
                media.width = (int) Json.num(address, "width", "Width");
                media.height = (int) Json.num(address, "height", "Height");
                media.bitrate = Json.num(gear, "bit_rate", "Bitrate", "bitRate");
                if (media.valid()) {
                    out.add(media);
                    addedLadder = true;
                }
            }
        }

        if (addedLadder) return;

        String play = Json.urlFrom(node, "play_addr", "playAddr", "download_addr", "downloadAddr");
        if (play == null) return;

        FoundMedia media = base(play, hint, thumbnail, durationMs);
        JsonObject address = Json.obj(node, "play_addr", "playAddr");
        if (address != null) {
            media.width = (int) Json.num(address, "width", "Width");
            media.height = (int) Json.num(address, "height", "Height");
        }
        if (media.width <= 0) media.width = (int) Json.num(node, "width");
        if (media.height <= 0) media.height = (int) Json.num(node, "height");
        if (media.valid()) out.add(media);
    }

    private FoundMedia base(String url, String hint, String thumbnail, long durationMs) {
        FoundMedia media = new FoundMedia(url);
        media.kind = url.toLowerCase(java.util.Locale.US).contains(".m3u8")
                ? MediaKind.HLS : MediaKind.PROGRESSIVE;
        media.thumbnail = thumbnail;
        media.durationMs = durationMs;
        media.groupHint = hint;
        return media;
    }

    /** The app API reports milliseconds, the web payload seconds. */
    private static long durationOf(JsonObject node) {
        long raw = Json.num(node, "duration", "Duration");
        if (raw <= 0) return 0;
        return raw < 1000 ? raw * 1000 : raw;
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "id", "Id", "aweme_id", "awemeId", "video_id", "vid");
        return id == null ? null : "tt:" + id;
    }
}
