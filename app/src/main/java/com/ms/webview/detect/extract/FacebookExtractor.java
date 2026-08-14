package com.ms.webview.detect.extract;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ms.webview.detect.MediaKind;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facebook watch, reels and feed video.
 *
 * <p>Facebook has moved its playables twice. The original flat pair — {@code playable_url} and
 * {@code playable_url_quality_hd} sitting on the video node — is still served to some clients,
 * but the current web payload wraps everything in a delivery fragment: the ladder arrives as a
 * {@code progressive_urls} list of {url, quality} pairs, and the adaptive form as
 * {@code dash_manifests} carrying the XML inline. Reading only the flat keys is why nothing was
 * being detected; all three shapes are handled here.
 *
 * <p>Grouping is the other half of it. A delivery fragment rarely carries the video id, so the
 * ladder entries have nothing in common to collapse on. Where the node names no video, the
 * entries are left unnamed and collapsed by the address they were fetched from instead — the
 * asset id Facebook writes into every media URL, which the platform's policy reads out. The
 * address bar is used only where it names one video rather than a feed, because a name shared
 * between two videos merges them into one card, and a card holding two videos downloads the
 * wrong one.
 */
public class FacebookExtractor implements SiteExtractor {

    /**
     * The muxed addresses first, and the order is the whole point.
     *
     * <p>These lists are read first-match-wins, and {@code playable_url} used to head both. That
     * is the address Facebook's own player feeds to Media Source Extensions, and it carries video
     * and nothing else — the sound arrives on a second stream the page fetches separately. Taking
     * it meant every Facebook download came out silent, and no later stage could tell: the file
     * was complete, correct, and mute.
     *
     * <p>{@code browser_native_*} and {@code *_src} are the plain progressive files, video and
     * audio together, which is what Facebook serves to anything that is not running its player —
     * and what every other downloader takes. They go first. {@code playable_url} stays at the back
     * as a last resort, because a silent video still beats no video at all.
     */
    private static final String[] SD_KEYS = {
            "browser_native_sd_url", "sd_src", "sd_src_no_ratelimit", "playable_url"
    };
    private static final String[] HD_KEYS = {
            "browser_native_hd_url", "hd_src", "hd_src_no_ratelimit", "playable_url_quality_hd"
    };
    private static final String[] MANIFEST_KEYS = {
            "dash_manifest", "dash_manifest_xml", "video_dash_manifest", "manifest_xml"
    };
    /** The single progressive URL on one entry of a delivery fragment's ladder. */
    private static final String[] PROGRESSIVE_KEYS = {
            "progressive_url", "playable_url_string", "url"
    };
    private static final String[] HLS_KEYS = {
            "hls_playback_url", "playable_url_hls", "hls_url"
    };

    /**
     * Addresses that are about one video and nothing else.
     *
     * <p>Deliberately not {@code /watch/?v=} or {@code /reel/}, though both carry an id. Those
     * are feeds: the id names the clip the feed opened on, and the page goes on to load every
     * neighbour beside it. Letting the address name a node meant every prefetched clip that
     * arrived without an id of its own was labelled as that one video, and a hint is what
     * collapses renditions into a card — so a dozen different clips merged into a single card
     * holding a dozen different files.
     *
     * <p>That is the card that shows one video, lists more qualities than a video has, and
     * downloads a different clip than the one on screen: the download takes the best rendition
     * on the card, and the card's renditions belong to several videos.
     *
     * <p>The mobile site's story.php redirects to the /videos/slug/id form, so that one is only
     * reached while the redirect is still in flight, but it costs nothing.
     */
    private static final Pattern PERMALINK_VIDEO_ID = Pattern.compile(
            "(?:/videos/(?:[^/?#]+/)?|/video\\.php\\?v=|[?&]story_fbid=)(\\d{6,})");

    @Override
    public boolean appliesTo(String host) {
        return host.contains("facebook.com") || host.contains("fb.watch")
                || host.contains("fbcdn.net") || host.contains("fbsbx.com");
    }

    @Override
    public void inspect(JsonObject node, String pageUrl, List<FoundMedia> out) {
        Context context = new Context(node, pageUrl);

        readDeliveryFragment(node, context, out);
        readFlatKeys(node, context, out);
    }

    /**
     * The current shape. {@code progressive_urls} and {@code dash_manifests} may sit on this
     * node or one level down under the response result, depending on which query produced it.
     */
    private void readDeliveryFragment(JsonObject node, Context context, List<FoundMedia> out) {
        JsonObject source = node;
        JsonObject nested = Json.obj(node,
                "videoDeliveryResponseResult", "videoDeliveryLegacyFields",
                "video_delivery_response_result");
        if (nested != null) source = nested;

        JsonArray progressive = Json.arr(source, "progressive_urls", "progressiveUrls");
        // The manifest first, because whether there is one decides what the plain addresses are
        // worth — see addManifest.
        boolean manifested = false;
        JsonArray manifests = Json.arr(source, "dash_manifests", "dashManifests");
        if (manifests != null) {
            for (JsonElement element : manifests) {
                if (!element.isJsonObject()) continue;
                String xml = Json.str(element.getAsJsonObject(), MANIFEST_KEYS);
                manifested |= addManifest(out, xml, context);
            }
        }

        if (!manifested && progressive != null) {
            for (JsonElement element : progressive) {
                if (!element.isJsonObject()) continue;
                JsonObject entry = element.getAsJsonObject();
                String url = Json.urlFrom(entry, PROGRESSIVE_KEYS);
                if (url == null) continue;
                add(out, url, heightOf(qualityOf(entry)), context);
            }
        }

        // Kept either way: Facebook's HLS carries its audio inside, so it costs nothing beside
        // the manifest and covers the payloads that ship one without the other.
        String hls = Json.str(source, HLS_KEYS);
        if (hls != null) add(out, hls, 0, context);
    }

    /** The older flat shape, still served to some clients and to fb.watch. */
    private void readFlatKeys(JsonObject node, Context context, List<FoundMedia> out) {
        if (addManifest(out, Json.str(node, MANIFEST_KEYS), context)) return;

        String hd = Json.str(node, HD_KEYS);
        String sd = Json.str(node, SD_KEYS);
        // Heights Facebook does not state. These order the ladder; they are not measurements,
        // and the prober corrects them once a URL is opened.
        if (hd != null) add(out, hd, 720, context);
        if (sd != null && !sd.equals(hd)) add(out, sd, 360, context);
    }

    /**
     * Takes the manifest, and says whether it took one.
     *
     * <p>That answer decides whether the plain addresses beside it are used at all, and both of
     * Facebook's long-standing complaints come from having preferred them.
     *
     * <p>The flat pair is exactly two rungs — {@code playable_url} and its HD twin — so a video
     * with six qualities in its manifest was offered as two. And on the current player those two
     * are video-only representations: Facebook plays through MSE and fetches its sound as a
     * separate stream, so a download of either is a picture with no soundtrack. Nothing later can
     * repair that, because the address of the audio was never in the record.
     *
     * <p>The manifest has neither problem. It lists the whole ladder and names the audio for each
     * rung, and the resolver already pairs them and muxes on the way out. So where there is one,
     * it is the only thing worth offering; where there is not, the plain addresses are still
     * better than nothing.
     */
    private boolean addManifest(List<FoundMedia> out, @Nullable String xml, Context context) {
        if (xml == null || !xml.contains("<MPD")) return false;

        String key = context.hint == null ? "fb" : context.hint.replace(':', '_');
        FoundMedia media = new FoundMedia("https://dash.local/" + key);
        media.kind = MediaKind.DASH;
        media.inlineManifest = xml;
        apply(media, context);
        out.add(media);

        // Taken either way, but it only earns the right to silence the plain addresses if it can
        // actually deliver sound. A manifest naming no audio track yields video-only renditions,
        // and suppressing the progressive pair in favour of those trades two working files for a
        // ladder of mute ones — which is the worse of the two complaints, not the better.
        return namesAudio(xml);
    }

    /**
     * Whether the manifest lists a separate audio track, read off the text rather than parsed.
     *
     * <p>Only the decision above rests on this, and it is made before anything is resolved, so a
     * full parse here would be work done twice. The resolver still does the real one.
     */
    private static boolean namesAudio(String xml) {
        String lower = xml.toLowerCase(Locale.US);
        return lower.contains("audio/mp4") || lower.contains("audio/webm")
                || lower.contains("\"audio\"") || lower.contains("=\"audio")
                || lower.contains("mp4a") || lower.contains("opus");
    }

    private void add(List<FoundMedia> out, String url, int height, Context context) {
        if (url == null || !url.startsWith("http")) return;

        FoundMedia media = new FoundMedia(url);
        media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
        media.height = height;
        apply(media, context);
        if (media.valid()) out.add(media);
    }

    private void apply(FoundMedia media, Context context) {
        media.groupHint = context.hint;
        media.thumbnail = context.thumbnail;
        media.title = context.title;
        media.author = context.author;
        media.durationMs = context.durationMs;
    }

    /** What the node says about the video, gathered once per visit. */
    private static class Context {
        final String hint;
        final String thumbnail;
        final String title;
        final String author;
        final long durationMs;

        Context(JsonObject node, String pageUrl) {
            hint = hintOf(node, pageUrl);

            thumbnail = thumbnailOf(node);
            title = titleOf(node);
            author = authorOf(node);

            long ms = Json.num(node, "playable_duration_in_ms", "durationMs");
            durationMs = ms > 0
                    ? ms
                    : Json.seconds(node, "playable_duration", "length_in_second", "duration");
        }
    }

    /**
     * The video's name.
     *
     * <p>Facebook almost never states this as a plain string. Its titles, captions and
     * descriptions are objects wrapping a {@code text} field, and a reel's caption is buried a
     * further level down under the creation story. Reading them as strings found nothing at
     * all, which is why these were falling back to the document title — "Facebook" on every
     * card.
     *
     * <p>Ordered by how specific each field is to the video rather than to the post around it.
     */
    @Nullable
    private static String titleOf(JsonObject node) {
        String direct = text(node, "title", "video_title", "name", "headline");
        if (direct != null) return direct;

        // A reel carries its caption on the story that created it, not on the video.
        JsonObject story = Json.obj(node, "creation_story", "story", "feedback_context");
        String storyText = text(story, "message", "title", "summary");
        if (storyText != null) return storyText;

        return text(node, "message", "savable_description", "description", "text");
    }

    /**
     * Who posted it.
     *
     * <p>Worth having because most videos in a Facebook response arrive with no caption at all
     * — the caption belongs to the story wrapped around the video, and the clips the page has
     * merely pre-loaded have no story with them and no element on the page to read one from.
     * Those cards were falling all the way through to the bare host. A page name is not a
     * title, but "Some Page" tells the user which video they are looking at and
     * "www.facebook.com" does not.
     */
    @Nullable
    private static String authorOf(JsonObject node) {
        JsonObject owner = Json.obj(node, "owner", "video_owner", "creation_actor", "actor");
        String name = Json.str(owner, "name", "short_name", "username");
        if (name != null) return name;

        // A story states its author as a list, because a post can have more than one.
        JsonObject story = Json.obj(node, "creation_story", "story");
        JsonObject actor = Json.first(Json.arr(story, "actors"));
        name = Json.str(actor, "name", "short_name", "username");
        if (name != null) return name;

        return Json.str(Json.obj(story, "actor", "owner"), "name", "short_name");
    }

    /**
     * A field that may be a bare string or an object with the string inside it. Facebook uses
     * both spellings for the same field depending on which query returned the node.
     */
    @Nullable
    private static String text(@Nullable JsonObject node, String... keys) {
        if (node == null) return null;
        for (String key : keys) {
            JsonElement element = node.get(key);
            if (element == null || element.isJsonNull()) continue;

            if (element.isJsonPrimitive()) {
                String value = element.getAsString();
                if (!TextUtils.isEmpty(value)) return clean(value);
            } else if (element.isJsonObject()) {
                String value = Json.str(element.getAsJsonObject(), "text", "delight_ranges_text");
                if (value != null) return clean(value);
            }
        }
        return null;
    }

    /** Captions run to paragraphs; a card shows one line, so give it the first sentence. */
    private static String clean(String raw) {
        String text = raw.trim().replaceAll("\\s+", " ");
        return text.length() <= 120 ? text : text.substring(0, 117).trim() + "…";
    }

    /** "HD"/"SD", or a "1080p"-style label, whichever this build of the site is using. */
    @Nullable
    private static String qualityOf(JsonObject entry) {
        String direct = Json.str(entry, "quality", "label", "rendition");
        if (direct != null) return direct;
        return Json.str(Json.obj(entry, "metadata"), "quality", "label");
    }

    private static int heightOf(@Nullable String quality) {
        if (quality == null) return 0;
        String q = quality.toLowerCase(Locale.US);
        // A named tier rather than a measurement: order it, do not claim it.
        if (q.equals("hd")) return 720;
        if (q.equals("sd")) return 360;
        try {
            return Integer.parseInt(q.replaceAll("\\D", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Which video this node belongs to, in the same words the DOM scanner uses.
     *
     * <p>This is what decides whether the card on screen is the video on screen. The scanner
     * can only ever say {@code fb:<numeric video id>}, because that is all a Facebook permalink
     * contains. So a numeric video id — from the node, or failing that from the address — is
     * the only thing allowed to claim that name.
     *
     * <p>Everything else gets {@code fbn:}. A node's {@code id} is usually an opaque global
     * handle and {@code post_id} names the post rather than the video, so neither is the
     * scanner's id; publishing them under {@code fb:} meant the playing video never found its
     * own card, and the sheet fell back to showing whichever was found most recently — some
     * other post, with some other post's title. The {@code fbn:} form still collapses one
     * video's renditions into a single card, which is the other job a hint has to do.
     *
     * <p>The address is consulted last and only for a permalink — see
     * {@link #PERMALINK_VIDEO_ID}. A node that yields no name at all is left unnamed rather than
     * borrowing the address of a feed: a nameless node's renditions are collapsed by the address
     * they were fetched from instead, which the platform's policy reads the video id out of, so
     * nothing is lost by refusing to guess here.
     */
    @Nullable
    private static String hintOf(JsonObject node, String pageUrl) {
        String numeric = firstNumeric(node, "video_id", "videoId");
        if (numeric == null) numeric = firstNumeric(node, "id");
        if (numeric == null) numeric = pageVideoId(pageUrl);
        if (numeric != null) return "fb:" + numeric;

        String opaque = Json.str(node, "id", "post_id", "postID");
        return opaque == null ? null : "fbn:" + opaque;
    }

    @Nullable
    private static String firstNumeric(JsonObject node, String... keys) {
        String value = Json.str(node, keys);
        if (value == null || value.length() < 6) return null;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return null;
        }
        return value;
    }

    /** The video a permalink is about, or null when the address is a feed. */
    @Nullable
    private static String pageVideoId(String pageUrl) {
        if (TextUtils.isEmpty(pageUrl)) return null;
        Matcher m = PERMALINK_VIDEO_ID.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }

    @Nullable
    private static String thumbnailOf(JsonObject node) {
        String uri = Json.str(Json.obj(Json.obj(node, "preferred_thumbnail"), "image"), "uri");
        if (uri != null) return uri;

        uri = Json.str(Json.obj(node, "thumbnailImage", "image", "photo_image"), "uri");
        if (uri != null) return uri;

        return Json.str(node, "thumbnail_uri", "thumbnailUrl", "preview_image_uri",
                "previewImage");
    }
}
