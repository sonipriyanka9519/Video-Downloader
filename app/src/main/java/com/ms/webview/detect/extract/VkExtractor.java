package com.ms.webview.detect.extract;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VK video.
 *
 * <p>The player states its ladder as keys rather than a list — {@code mp4_240} through
 * {@code mp4_1080}, alongside {@code hls} and {@code dash_sep} — either directly on the node or
 * nested under {@code files}. The key carries the height, so the ladder needs no inference.
 *
 * <p>A VK video page ships the player payload for the video being watched <em>and</em> for every
 * recommendation beside it, which is why an unfiltered read produced a couple of dozen entries
 * for one video. When the address bar names a video, only that one is taken.
 */
public class VkExtractor implements SiteExtractor {

    /** Matches mp4_720, url720 and the occasional cache720. */
    private static final Pattern HEIGHT_KEY =
            Pattern.compile("^(?:mp4_|url|cache)(\\d{3,4})$", Pattern.CASE_INSENSITIVE);
    /** The owner/video pair VK puts in its addresses: /video-12345_67890. */
    private static final Pattern PAGE_VIDEO_ID =
            Pattern.compile("video(-?\\d+)_(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean appliesTo(String host) {
        return host.contains("vk.com") || host.contains("vkvideo.ru")
                || host.contains("userapi.com") || host.contains("vk-cdn.net")
                || host.contains("vkuservideo");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        // Only the video the address bar names, and nothing else.
        //
        // VK ships a full player payload for every recommendation beside the one being watched,
        // and its feed URLs carry the open video as ?z=video-123_456 — so the address is always
        // the authority on which is current. No address, no match, no id on the node: all three
        // mean we cannot prove this is the video on screen, and offering it anyway is what
        // produced two dozen entries for one page.
        String pageId = pageVideoId(pageUrl);
        if (pageId == null) return;

        String nodeId = idOf(node);
        if (nodeId == null || !pageId.equals(nodeId)) return;

        JsonObject files = Json.obj(node, "files");
        JsonObject source = files != null ? files : node;

        String hint = "vk:" + nodeId;
        String thumbnail = Json.str(node, "jpg", "image", "thumb", "first_frame", "photo_800");
        String title = Json.str(node, "title", "description");
        long durationMs = Json.seconds(node, "duration");

        for (String key : source.keySet()) {
            JsonElement value = source.get(key);
            if (value == null || !value.isJsonPrimitive()) continue;

            String url = value.getAsString();
            if (url == null || !url.startsWith("http")) continue;

            MediaKind kind = kindOf(key);
            if (kind == null) continue;

            FoundMedia media = new FoundMedia(url);
            media.kind = kind;
            media.height = heightOf(key);
            media.thumbnail = thumbnail;
            media.title = title;
            media.durationMs = durationMs;
            media.groupHint = hint;
            if (media.valid()) out.add(media);
        }
    }

    /**
     * What a key represents, or null when it is not a playable rendition.
     *
     * <p>Live variants are excluded deliberately: a stream has no end to download to, and VK
     * puts its live addresses under the same {@code files} object as everything else.
     */
    @Nullable
    private static MediaKind kindOf(String key) {
        String k = key.toLowerCase(Locale.US);
        if (k.startsWith("live") || k.startsWith("flv") || k.startsWith("external")) return null;
        if (k.startsWith("dash")) return MediaKind.DASH;
        if (k.startsWith("hls")) return MediaKind.HLS;
        return HEIGHT_KEY.matcher(key).matches() ? MediaKind.PROGRESSIVE : null;
    }

    private static int heightOf(String key) {
        Matcher m = HEIGHT_KEY.matcher(key);
        if (!m.matches()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** "-12345_67890" for the node, so it can be compared against the address bar. */
    @Nullable
    private static String idOf(JsonObject node) {
        String owner = Json.str(node, "oid", "owner_id", "ownerId");
        String id = Json.str(node, "vid", "video_id", "videoId", "id");
        if (id == null) return null;
        return owner == null ? id : owner + "_" + id;
    }

    @Nullable
    private static String pageVideoId(String pageUrl) {
        if (TextUtils.isEmpty(pageUrl)) return null;
        Matcher m = PAGE_VIDEO_ID.matcher(pageUrl);
        return m.find() ? m.group(1) + "_" + m.group(2) : null;
    }
}
