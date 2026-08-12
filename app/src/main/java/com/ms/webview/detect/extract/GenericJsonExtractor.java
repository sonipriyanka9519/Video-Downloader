package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The safety net, and the workhorse for news sites and embedded players.
 *
 * <p>Handles two shapes. A flat node with a single video URL under one of the usual key names,
 * and — more importantly for anything running JW Player, Brightcove or a house player — a
 * quality-ladder array where each entry is one rendition. That ladder is normally the only
 * place a page states its full set of qualities, and it is present in the server-rendered
 * JSON long before any {@code <video>} element exists.
 */
public class GenericJsonExtractor implements SiteExtractor {

    private static final String[] URL_KEYS = {
            "videoUrl", "video_url", "playbackUrl", "playback_url", "contentUrl", "content_url",
            "hlsUrl", "hls_url", "streamUrl", "stream_url", "mp4", "mp4Url", "fileUrl",
            "downloadUrl", "download_url", "manifestUrl", "masterPlaylistUrl", "source", "src"
    };
    private static final String[] THUMB_KEYS = {
            "thumbnail", "thumbnailUrl", "thumbnail_url", "thumb", "thumbUrl", "poster",
            "posterUrl", "cover", "coverUrl", "coverImage", "previewUrl", "image", "imageUrl"
    };
    private static final String[] TITLE_KEYS = {"title", "caption", "name", "headline", "description"};
    /** Arrays whose entries are renditions of one video. */
    private static final String[] LADDER_KEYS = {
            "sources", "renditions", "qualities", "formats", "streams", "stream", "videoFiles",
            "media_files", "progressive", "playlist", "media"
    };
    /**
     * Per-entry URL keys inside a ladder. JW Player — which Fandom and a great many news sites
     * run — uses "file"; Flickr puts the URL in "_content".
     */
    private static final String[] ENTRY_URL_KEYS = {
            "file", "url", "src", "link", "href", "_content"
    };
    private static final String[] ENTRY_LABEL_KEYS = {
            "label", "quality", "res", "resolution", "name", "type"
    };

    private static final Pattern HEIGHT_IN_LABEL = Pattern.compile("(\\d{3,4})\\s*[pP]?");

    @Override
    public boolean appliesTo(String host) {
        return true;
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        boolean foundLadder = inspectLadder(node, out);
        if (!foundLadder) inspectFlat(node, out);
    }

    /** A node carrying an array of renditions: one card, many qualities. */
    private boolean inspectLadder(JsonObject node, List<FoundMedia> out) {
        JsonArray ladder = Json.arr(node, LADDER_KEYS);
        if (ladder == null || ladder.size() == 0) return false;

        String thumbnail = Json.str(node, THUMB_KEYS);
        String title = Json.str(node, TITLE_KEYS);
        String hint = hintOf(node);
        long duration = durationOf(node);

        boolean added = false;
        for (JsonElement element : ladder) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();

            String url = Json.str(entry, ENTRY_URL_KEYS);
            if (url == null || !url.startsWith("http")) continue;

            FoundMedia media = new FoundMedia(url);
            media.kind = kindOf(url, Json.str(entry, "type", "mimeType", "mime_type"));
            media.thumbnail = thumbnail;
            media.title = title;
            media.durationMs = duration;
            media.groupHint = hint;
            media.bitrate = Json.num(entry, "bitrate", "bandwidth", "bitRate");
            media.width = (int) Json.num(entry, "width");
            media.height = (int) Json.num(entry, "height");
            if (media.height <= 0) media.height = heightFromLabel(Json.str(entry, ENTRY_LABEL_KEYS));

            if (media.valid()) {
                out.add(media);
                added = true;
            }
        }
        return added;
    }

    private void inspectFlat(JsonObject node, List<FoundMedia> out) {
        String url = Json.str(node, URL_KEYS);
        // "src" and "source" are used for all sorts of things, so require it to look like media.
        if (url == null || !Json.looksLikeMediaUrl(url)) return;

        FoundMedia media = new FoundMedia(url);
        media.kind = kindOf(url, Json.str(node, "type", "mimeType"));
        media.thumbnail = Json.str(node, THUMB_KEYS);
        media.title = Json.str(node, TITLE_KEYS);
        media.width = (int) Json.num(node, "width", "videoWidth");
        media.height = (int) Json.num(node, "height", "videoHeight");
        media.bitrate = Json.num(node, "bitrate", "bandwidth");
        media.durationMs = durationOf(node);
        media.groupHint = hintOf(node);

        if (media.valid()) out.add(media);
    }

    private static MediaKind kindOf(String url, String declaredType) {
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8")) return MediaKind.HLS;
        if (lower.contains(".mpd")) return MediaKind.DASH;
        if (declaredType != null) {
            String t = declaredType.toLowerCase(Locale.US);
            if (t.contains("mpegurl") || t.equals("hls")) return MediaKind.HLS;
            if (t.contains("dash")) return MediaKind.DASH;
        }
        return MediaKind.PROGRESSIVE;
    }

    /** "720p", "HD 1080", "480" — players label renditions rather than state a height. */
    private static int heightFromLabel(String label) {
        if (label == null) return 0;
        Matcher m = HEIGHT_IN_LABEL.matcher(label);
        if (!m.find()) return 0;
        try {
            int value = Integer.parseInt(m.group(1));
            return value >= 100 && value <= 4320 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long durationOf(JsonObject node) {
        long ms = Json.num(node, "durationMs", "duration_ms", "duration_millis");
        return ms > 0 ? ms : Json.seconds(node, "duration", "length", "videoDuration");
    }

    private static String hintOf(JsonObject node) {
        String id = Json.str(node, "mediaid", "mediaId", "videoId", "video_id", "id", "uuid", "guid");
        return id == null ? null : "gen:" + id;
    }
}
