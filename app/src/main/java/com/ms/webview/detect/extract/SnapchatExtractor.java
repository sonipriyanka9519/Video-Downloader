package com.ms.webview.detect.extract;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.UrlClassifier;

import java.util.List;
import java.util.Locale;

/**
 * Snapchat spotlight and public stories.
 *
 * <p>The playable sits under a metadata object rather than on the snap itself, and Snapchat
 * ships an image and a video under near-identical key names — so anything that does not look
 * like a media address is dropped rather than trusted.
 *
 * <p>Only public content reaches this. A snap sent to someone is not served to the web at all,
 * and nothing here changes that.
 */
public class SnapchatExtractor implements SiteExtractor {

    private static final String[] URL_KEYS = {
            "contentUrl", "mediaUrl", "videoUrl", "mediaPreviewUrl", "src"
    };
    private static final String[] COVER_KEYS = {
            "thumbnailUrl", "posterUrl", "previewUrl", "imageUrl", "mediaPreviewUrl"
    };

    @Override
    public boolean appliesTo(String host) {
        return host.contains("snapchat.com") || host.contains("sc-cdn.net")
                || host.contains("snapchat.dev") || host.contains("snap.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        // The snap wraps its playable; some payloads state it directly.
        JsonObject source = Json.obj(node, "videoMetadata", "snapMediaInfo", "mediaInfo");
        if (source == null) source = node;

        String url = Json.urlFrom(source, URL_KEYS);
        if (!playable(url)) return;

        FoundMedia media = new FoundMedia(url);
        media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
        media.thumbnail = Json.urlFrom(node, COVER_KEYS);
        media.title = Json.str(node, "description", "title", "caption", "snapTitle");
        media.author = Json.str(Json.obj(node, "creator", "publisher", "user"),
                "displayName", "username", "name");
        media.width = (int) Json.num(source, "width", "videoWidth");
        media.height = (int) Json.num(source, "height", "videoHeight");
        media.durationMs = durationOf(source);
        media.groupHint = hintOf(node);

        if (media.valid()) out.add(media);
    }

    /**
     * Whether an address is worth offering as this snap's video.
     *
     * <p>The shared test asks for a file extension, and Snapchat's does not have one. Its CDN
     * serves every snap from a path like {@code /d/<token>} with the format in the query, so
     * {@code .mp4} appears nowhere in the address — and a rule written around extensions rejects
     * the whole platform. That is why a page with a snap plainly playing on it detected nothing
     * whatsoever.
     *
     * <p>So an extension-less address is accepted, but only from Snapchat's own media hosts.
     * Anywhere else the extension is still required: relaxing that generally would offer every
     * unrecognised link on every site as a candidate video.
     *
     * <p>Images are still refused, because Snapchat publishes a still and a clip under the same
     * key names — {@code mediaPreviewUrl} appears in both lists here. An extension-less still
     * will slip past this and be dropped a step later, when the probe opens it and finds no video
     * track; the point of the check is to keep the obvious ones out, not to be the last word.
     */
    private static boolean playable(@Nullable String url) {
        if (url == null || !url.startsWith("http")) return false;
        if (UrlClassifier.isImage(url)) return false;
        return Json.looksLikeMediaUrl(url) || isSnapchatMedia(url);
    }

    /** Snapchat's own delivery hosts, which is where a snap's playable always comes from. */
    private static boolean isSnapchatMedia(String url) {
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("sc-cdn.net") || lower.contains("snapchat.com/web/deeplink/snap")
                || lower.contains("sc-prod.net");
    }

    /** Snapchat states seconds, occasionally fractional. */
    private static long durationOf(JsonObject node) {
        long ms = Json.num(node, "durationMs", "durationMillis");
        if (ms > 0) return ms;
        return Json.seconds(node, "duration", "durationSeconds");
    }

    @Nullable
    private static String hintOf(JsonObject node) {
        String id = Json.str(node, "snapId", "snapID", "storyId", "id");
        return id == null ? null : "snap:" + id;
    }
}
