package com.ms.webview.detect.extract;

import androidx.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pinterest, including Idea pins.
 *
 * <p>A pin's {@code video_list} is a map keyed by format name — {@code V_720P},
 * {@code V_HLSV4} and friends — each entry carrying its own url, dimensions and thumbnail.
 * Idea pins nest the same structure inside story pages, which the walker reaches on its own.
 */
public class PinterestExtractor implements SiteExtractor {

    private static final Pattern FORMAT_HEIGHT =
            Pattern.compile("(\\d{3,4})P?", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean appliesTo(String host) {
        return host.contains("pinterest.") || host.contains("pinimg.com")
                || host.contains("pin.it");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        JsonObject videos = Json.obj(node, "videos");
        JsonObject list = videos != null ? Json.obj(videos, "video_list") : Json.obj(node, "video_list");
        if (list == null) return;

        String hint = hintOf(node);
        // The walker offers every object in the tree, so this list is reached twice: once from
        // the pin, which carries the id, and once from the "videos" wrapper inside it, which
        // does not. Whichever arrives first decides how the qualities are keyed — and the
        // unhinted one keyed them by their own addresses, so a video with three renditions
        // became three cards holding one quality each.
        //
        // Every entry in one video_list is the same video by definition, so when the pin id is
        // out of reach the list names itself instead. Both passes still land on one card: the
        // second finds these URLs already owned and joins them.
        if (hint == null) hint = listIdentity(list);

        String fallbackThumb = thumbnailOf(node);
        String title = Json.str(node, "title", "grid_title", "description");

        for (String format : list.keySet()) {
            JsonElement element = list.get(format);
            if (element == null || !element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();

            String url = Json.str(entry, "url");
            if (url == null) continue;

            FoundMedia media = new FoundMedia(url);
            media.kind = kindOf(url, format);
            media.width = (int) Json.num(entry, "width");
            media.height = (int) Json.num(entry, "height");
            media.thumbnail = Json.str(entry, "thumbnail");
            if (media.thumbnail == null) media.thumbnail = fallbackThumb;
            media.title = title;
            media.durationMs = (long) Json.num(entry, "duration");
            media.groupHint = hint;

            // The format name states the height when the entry itself does not.
            if (media.height <= 0) media.height = heightFromFormat(format);
            if (media.valid()) out.add(media);
        }
    }

    private static MediaKind kindOf(String url, String format) {
        String lower = url.toLowerCase(Locale.US);
        if (lower.contains(".m3u8")) return MediaKind.HLS;
        if (lower.contains(".mpd")) return MediaKind.DASH;
        if (format != null && format.toUpperCase(Locale.US).contains("HLS")) return MediaKind.HLS;
        return MediaKind.PROGRESSIVE;
    }

    /** "V_720P" -> 720. Returns 0 for the adaptive entries, which carry no fixed height. */
    private static int heightFromFormat(String format) {
        if (format == null) return 0;
        Matcher m = FORMAT_HEIGHT.matcher(format);
        if (!m.find()) return 0;
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String thumbnailOf(JsonObject node) {
        JsonObject images = Json.obj(node, "images");
        if (images != null) {
            JsonObject original = Json.obj(images, "orig", "originals");
            String url = Json.str(original, "url");
            if (url != null) return url;
            String keyed = Json.largestKeyedUrl(images);
            if (keyed != null) return keyed;
        }
        return Json.str(node, "image_large_url", "image_url", "thumbnail");
    }

    private String hintOf(JsonObject node) {
        String id = Json.str(node, "id", "pin_id", "entity_id");
        return id == null ? null : "pin:" + id;
    }

    /**
     * A name for a {@code video_list} that has no pin id above it, taken from the list itself.
     *
     * <p>The smallest address rather than the first, so the answer cannot depend on the order
     * the formats happen to be listed in — the same list must always produce the same name, or
     * it groups nothing.
     *
     * <p>Its own {@code pinv:} namespace: this says "these renditions are one video", which is
     * a weaker claim than the pin id's "this is that pin", and the two must not be confused for
     * one another.
     */
    @Nullable
    private static String listIdentity(JsonObject list) {
        String lowest = null;
        for (String format : list.keySet()) {
            JsonElement element = list.get(format);
            if (element == null || !element.isJsonObject()) continue;
            String url = Json.str(element.getAsJsonObject(), "url");
            if (url == null) continue;
            if (lowest == null || url.compareTo(lowest) < 0) lowest = url;
        }
        return lowest == null ? null : "pinv:" + lowest;
    }
}
