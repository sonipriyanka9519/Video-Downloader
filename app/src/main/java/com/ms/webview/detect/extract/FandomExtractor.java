package com.ms.webview.detect.extract;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.site.FandomPolicy;

import java.util.List;
import java.util.Locale;

/**
 * Fandom / Wikia featured video.
 *
 * <p>A wiki page ships one player payload holding the video that plays and the whole queue of
 * clips lined up behind it — often a dozen. Taking all of them filled the sheet with videos
 * nobody had asked for, so this reads the playing one and nothing else.
 *
 * <p>The way it does that matters more than it looks. Only <em>container</em> nodes are read —
 * the playlist, the player payload — and only their first entry, which is the one the player
 * loads. Individual queue entries are ignored outright, even though the walker offers every one
 * of them. That is the difference between "the first video" and "whichever video happened to be
 * visited first".
 *
 * <p>Three guards stand in front of all that, because reading the payload correctly is no help
 * if the payload was never this page's to begin with. Fandom asks for a recommendation rail on
 * every page whether or not it has a video of its own, and accepting one is what put a download
 * card on a page of plain text. A payload is dropped if it came from one of those addresses, if
 * it describes itself as promoted or suggested, or if the address names a clip and this is a
 * different one.
 *
 * <p>Changing video is not a page load on Fandom: the player fetches a fresh payload whose first
 * entry is the newly selected clip, which arrives here and joins the sheet behind the one
 * already there. A reload starts the whole thing over with whatever is featured then.
 */
public class FandomExtractor implements SiteExtractor {

    /** Where the playing clip sits, in the spellings Fandom's players use. */
    private static final String[] PLAYLIST_KEYS = {
            "playlist", "playlists", "videos", "items", "mediaItems"
    };
    /** A player payload that carries its one video directly instead of as a list. */
    private static final String[] SINGLE_KEYS = {
            "videoDetails", "video", "currentVideo", "featuredVideo", "playerData"
    };
    private static final String[] SOURCE_KEYS = {"sources", "files", "renditions", "streams"};
    private static final String[] FILE_KEYS = {"file", "src", "url", "source", "path"};
    private static final String[] IMAGE_KEYS = {
            "image", "thumbnail", "thumbnailUrl", "poster", "posterUrl", "thumbUrl"
    };

    @Override
    public boolean appliesTo(String host) {
        return host.contains("fandom.com") || host.contains("wikia.com")
                || host.contains("wikia.nocookie.net") || host.contains("wikia-services.com")
                || host.contains("nocookie.net") || host.contains("wikia.org");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        // Came from a recommendation or partner endpoint rather than the article. The list of
        // what those look like belongs to Fandom's policy, not here.
        if (FandomPolicy.isRecommendationUrl(pageUrl)) return;

        // Says so about itself, whatever address it arrived from.
        if (isRecommendationPayload(node)) return;

        JsonObject current = currentVideo(node);
        // Not a container, or a container we cannot read: a bare queue entry lands here and is
        // deliberately dropped. Letting those through is exactly how the whole queue got in.
        if (current == null) return;

        // The address names a clip and this is not it, so it is something offered alongside the
        // video rather than the video.
        if (!isRequestedMedia(current, pageUrl)) return;

        String hint = hintOf(current);
        String title = Json.str(current, "title", "name", "headline", "caption");
        String thumbnail = Json.urlFrom(current, IMAGE_KEYS);
        long durationMs = durationOf(current);

        JsonArray sources = Json.arr(current, SOURCE_KEYS);
        if (sources != null) {
            for (JsonElement element : sources) {
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();
                add(out, Json.urlFrom(source, FILE_KEYS), labelOf(source),
                        hint, title, thumbnail, durationMs);
            }
        }

        // Some payloads state one address on the video itself rather than a ladder beside it.
        add(out, Json.urlFrom(current, FILE_KEYS), null, hint, title, thumbnail, durationMs);
    }

    /**
     * True when the payload names itself as promoted, suggested or global content rather than an
     * article's own player data.
     *
     * <p>Read on the wrapper the walker handed over, not on the video inside it, so a
     * recommendation rail carrying a perfectly real-looking clip is still refused.
     */
    private static boolean isRecommendationPayload(JsonObject node) {
        String type = Json.str(node, "type", "context", "source", "kind");
        if (type != null) {
            String t = type.toLowerCase(Locale.US);
            if (t.contains("recommend") || t.contains("sponsor") || t.contains("partner")
                    || t.contains("trending") || t.contains("featured_global")
                    || t.contains("suggested")) {
                return true;
            }
        }

        if (jsonBool(node, "isRecommendation") || jsonBool(node, "sponsored")
                || jsonBool(node, "isAd") || jsonBool(node, "isFeaturedGlobal")
                || jsonBool(node, "isPartnerSlot")) {
            return true;
        }

        // A response built around a recommendations array is a recommendation response.
        return node.has("recommendations");
    }

    /**
     * True when this is the clip the address asked for.
     *
     * <p>A hub address names its video outright, and the record names itself, so the two can
     * simply be compared. That is worth more than any amount of reasoning about what a payload
     * looks like: a recommendation carries a real title, a real poster and a real file, and is
     * indistinguishable from the video being watched except in one respect — it is a different
     * video. This is that respect, checked directly.
     *
     * <p>Both unknowns are read as consent rather than refusal. An address naming no clip is a
     * wiki article, where this test has nothing to say and the two guards before it do the work.
     * A record naming no id is not evidence of anything either, and refusing it would drop plain
     * embeds to a rule aimed at rails.
     */
    private static boolean isRequestedMedia(JsonObject video, String pageUrl) {
        String wanted = FandomPolicy.mediaIdIn(pageUrl);
        if (wanted == null) return true;

        String actual = Json.str(video, "mediaid", "mediaId", "videoId", "id", "contentId", "uid");
        if (actual == null) return true;

        return wanted.equalsIgnoreCase(actual);
    }

    private static boolean jsonBool(JsonObject node, String key) {
        if (node == null || !node.has(key)) return false;
        JsonElement element = node.get(key);
        if (!element.isJsonPrimitive()) return false;
        try {
            return element.getAsBoolean();
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * The clip the player is on, or null when this node is not a player payload at all.
     *
     * <p>First entry, never a later one. Fandom orders its playlist with the featured clip at
     * the front, and a fresh payload arrives whenever the viewer picks a different one — so the
     * front of the list is always what is playing right now.
     */
    @Nullable
    private static JsonObject currentVideo(JsonObject node) {
        JsonArray playlist = Json.arr(node, PLAYLIST_KEYS);
        if (playlist != null) {
            JsonObject first = Json.first(playlist);
            if (playable(first)) return first;
        }

        JsonObject single = Json.obj(node, SINGLE_KEYS);
        if (playable(single)) return single;

        return null;
    }

    /** A node is only worth reading if it actually carries somewhere to play from. */
    private static boolean playable(@Nullable JsonObject node) {
        if (node == null) return false;
        if (Json.arr(node, SOURCE_KEYS) != null) return true;
        String direct = Json.urlFrom(node, FILE_KEYS);
        return direct != null && Json.looksLikeMediaUrl(direct);
    }

    private void add(List<FoundMedia> out, @Nullable String url, @Nullable String label,
                     String hint, String title, String thumbnail, long durationMs) {
        if (url == null || !url.startsWith("http")) return;
        if (!Json.looksLikeMediaUrl(url)) return;

        FoundMedia media = new FoundMedia(url);
        media.kind = url.contains(".m3u8") ? MediaKind.HLS
                : url.contains(".mpd") ? MediaKind.DASH : MediaKind.PROGRESSIVE;
        media.height = heightOf(label);
        media.title = title;
        media.thumbnail = thumbnail;
        media.durationMs = durationMs;
        media.groupHint = hint;
        if (media.valid()) out.add(media);
    }

    @Nullable
    private static String labelOf(JsonObject source) {
        String label = Json.str(source, "label", "quality", "name", "resolution");
        if (label != null) return label;
        long height = Json.num(source, "height", "videoHeight");
        return height > 0 ? String.valueOf(height) : null;
    }

    /** "720p", "1080", "hd" — Fandom's labels are not consistent between players. */
    private static int heightOf(@Nullable String label) {
        if (label == null) return 0;
        String l = label.toLowerCase(Locale.US);
        if (l.equals("hd")) return 720;
        if (l.equals("sd")) return 360;
        try {
            return Integer.parseInt(l.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long durationOf(JsonObject node) {
        long ms = Json.num(node, "durationMs", "duration_ms");
        if (ms > 0) return ms;
        return Json.seconds(node, "duration", "length", "lengthSeconds");
    }

    /**
     * Keyed on the clip, not the page. A wiki page can be watched through several videos
     * without ever navigating, and each needs its own card.
     */
    @Nullable
    private static String hintOf(JsonObject node) {
        String id = Json.str(node, "mediaId", "mediaid", "videoId", "id", "contentId", "uid");
        return id == null ? null : "fandom:" + id;
    }
}
