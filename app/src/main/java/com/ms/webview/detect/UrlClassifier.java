package com.ms.webview.detect;

import android.net.Uri;
import android.text.TextUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies a URL by shape alone. This runs on the WebView's network thread for every single
 * subresource, so it must stay allocation-light and must never touch the network — anything
 * genuinely ambiguous is handed to {@link Prober} instead.
 */
public final class UrlClassifier {

    private static final Pattern PROGRESSIVE =
            Pattern.compile("\\.(mp4|m4v|webm|mkv|mov|3gp|flv|avi)(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern HLS =
            Pattern.compile("\\.m3u8(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH =
            Pattern.compile("\\.mpd(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SEGMENT =
            Pattern.compile("\\.(ts|m4s)(\\?|#|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IMAGE =
            Pattern.compile("\\.(jpg|jpeg|png|gif|webp|bmp|heic|heif|svg|ico)(\\?|#|$)",
                    Pattern.CASE_INSENSITIVE);
    /**
     * A byte-range slice rather than a whole file. Instagram and Facebook feed MediaSource by
     * fetching these, and they are the single biggest source of junk detections: a few hundred
     * kilobytes of a stream, with no moov atom, that can never be played or downloaded alone.
     */
    private static final Pattern PARTIAL_FETCH =
            Pattern.compile("[?&](byteend|bytestart)=(\\d+)", Pattern.CASE_INSENSITIVE);
    /** Query-string forms used by CDNs that serve extension-less media paths. */
    private static final Pattern MIME_IN_QUERY =
            Pattern.compile("(mime|content_type|ctype)=video(/|%2f)", Pattern.CASE_INSENSITIVE);
    /** Path shapes that usually mean video even without an extension. */
    private static final Pattern VIDEOISH_PATH =
            Pattern.compile("/(videoplayback|video|media|stream|hls|dash|play)(/|\\?|$)",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Delivery hosts that serve media from addresses carrying no extension and no telling path.
     *
     * <p>Every rule above this reads the address for evidence — a suffix, a mime in the query, a
     * directory named after what it holds. Some CDNs give none of it: Snapchat serves each snap
     * from {@code /d/<token>} with the format hidden in an opaque parameter, so the address is
     * indistinguishable from any other request and the whole platform was discarded before
     * anything could look at it.
     *
     * <p>Named hosts rather than a looser path rule, because the looseness is the danger: a rule
     * general enough to admit {@code /d/<token>} would admit most of the web. These hosts serve
     * media and little else, and what they hand back is still probed like any other guess — this
     * only buys the address the right to be checked.
     */
    private static final Pattern MEDIA_HOST = Pattern.compile(
            "(sc-cdn\\.net|sc-prod\\.net)", Pattern.CASE_INSENSITIVE);

    /**
     * Hosts whose responses are never worth probing — analytics, ads, fonts, thumbnails.
     *
     * <p>{@code gstatic} and {@code 2mdn} earn their place. Google's thumbnail service answers
     * at {@code encrypted-vtbn0.gstatic.com/video?q=tbn:…} with a real 38 KB, 256x144, six
     * second mp4 — it probes clean, decodes, yields a frame, and becomes a card indistinguishable
     * from a genuine find. On a Dailymotion page it was the only thing detected, and the player
     * was matched to it. {@code 2mdn} is the ad server whose {@code /instream/video/} paths look
     * like media for the same reason.
     */
    private static final Pattern NOISE_HOST = Pattern.compile(
            "(google-analytics|googletagmanager|doubleclick|facebook\\.net|scorecardresearch"
                    + "|hotjar|sentry\\.io|amplitude|segment\\.io|fonts\\.g"
                    + "|gstatic\\.com|2mdn\\.net|adservice\\."
                    // Vimeo's picture host. It serves thumbnails and nothing else, but every one
                    // of them sits under /video/ with no extension — so the path-shape rule at
                    // the bottom of classify() called each a possible video and sent it to be
                    // probed. A Vimeo page carries a dozen or more, and the probe budget they
                    // spent was budget the real media then could not have.
                    + "|i\\.vimeocdn\\.com)",
            Pattern.CASE_INSENSITIVE);

    /**
     * Extensions that are never media, whatever the rest of the path suggests.
     *
     * <p>Checked before the path-shape guess, which is the loosest rule here and the one that
     * mistakes {@code /instream/video/client.js} for a video on the strength of one directory
     * name.
     */
    private static final Pattern NON_MEDIA_EXT = Pattern.compile(
            "\\.(js|mjs|css|json|html?|xml|woff2?|ttf|map)(\\?|#|$)", Pattern.CASE_INSENSITIVE);

    private UrlClassifier() {
    }

    public static MediaKind classify(String url) {
        if (TextUtils.isEmpty(url)) return MediaKind.NONE;
        // blob: and data: cannot be re-fetched out of band; MSE handling is a later milestone.
        if (url.startsWith("blob:") || url.startsWith("data:")) return MediaKind.NONE;
        if (!url.startsWith("http")) return MediaKind.NONE;
        if (NOISE_HOST.matcher(url).find()) return MediaKind.NONE;
        if (IMAGE.matcher(url).find()) return MediaKind.NONE;
        // A slice of a stream is treated like any other segment: a hint that adaptive playback
        // is happening, never something to offer the user.
        if (isPartialFetch(url)) return MediaKind.SEGMENT;
        // Encrypted media that is not a manifest. A protected stream delivers dozens of these,
        // each of which probes clean as video/mp4, is created as an item, is opened, fails to
        // decode because it is encrypted, and is thrown away — over and over, spending the
        // budget the rest of the page needs. The manifests are let through: those become one
        // card that says Protected, which is the honest answer and worth one entry.
        if (declaresDrm(url) && !HLS.matcher(url).find() && !DASH.matcher(url).find()) {
            return MediaKind.NONE;
        }

        if (HLS.matcher(url).find()) return MediaKind.HLS;
        if (DASH.matcher(url).find()) return MediaKind.DASH;
        if (PROGRESSIVE.matcher(url).find()) return MediaKind.PROGRESSIVE;
        if (SEGMENT.matcher(url).find()) return MediaKind.SEGMENT;
        if (MIME_IN_QUERY.matcher(url).find()) return MediaKind.PROGRESSIVE;
        // Last and loosest: a directory called "video" is a hint, not a fact, so anything that
        // has already named itself something else is not reconsidered on those grounds.
        if (NON_MEDIA_EXT.matcher(url).find()) return MediaKind.NONE;
        if (VIDEOISH_PATH.matcher(url).find()) return MediaKind.UNKNOWN;
        // A host that only ever serves media, where the address itself says nothing at all.
        // UNKNOWN, not PROGRESSIVE: this is a reason to look, not a claim about what is there.
        if (MEDIA_HOST.matcher(url).find()) return MediaKind.UNKNOWN;
        return MediaKind.NONE;
    }

    public static boolean isImage(String url) {
        return !TextUtils.isEmpty(url) && IMAGE.matcher(url).find();
    }

    /**
     * An address that states outright that what it serves is encrypted.
     *
     * <p>{@code cenc} and {@code cbcs} are the two Common Encryption schemes — the ones Widevine,
     * PlayReady and FairPlay are built on — and a CDN that puts them in the path is not being
     * coy. Vimeo builds addresses like {@code /v2/playlist/drm/cenc,derivedv2,…/playlist.mpd}.
     *
     * <p>Worth reading before fetching anything, because the manifest behind such an address is
     * often not readable either: the licence has to be acquired first, so the parse that would
     * normally discover the encryption returns nothing at all, and the stream ends up looking
     * merely broken rather than protected. Every segment then fails to decode, one after another,
     * spending the budget that the rest of the page needed.
     */
    public static boolean declaresDrm(String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains("/drm/cenc,") || lower.contains("/drm/cbcs,")
                || lower.contains("/drm/cenc/") || lower.contains("/drm/cbcs/");
    }

    /**
     * True when the server has told us outright that this is not video. Distinct from "we could
     * not tell": an unknown type may still be media and is worth guessing about from the URL,
     * whereas {@code image/jpeg} never is.
     */
    public static boolean isNonMediaMime(String mime) {
        if (TextUtils.isEmpty(mime)) return false;
        String m = mime.toLowerCase(Locale.US);
        return m.startsWith("image/") || m.startsWith("text/") || m.startsWith("audio/")
                || m.startsWith("application/json") || m.startsWith("application/xml");
    }

    /** True when the URL asks for a byte range, so the response is part of a file, not one. */
    public static boolean isPartialFetch(String url) {
        if (TextUtils.isEmpty(url)) return false;
        Matcher m = PARTIAL_FETCH.matcher(url);
        while (m.find()) {
            // bytestart=0 on its own still means the whole file; byteend never does.
            if ("byteend".equalsIgnoreCase(m.group(1))) return true;
            if (!"0".equals(m.group(2))) return true;
        }
        return false;
    }

    public static MediaKind fromMime(String mime) {
        if (TextUtils.isEmpty(mime)) return MediaKind.NONE;
        String m = mime.toLowerCase(Locale.US);
        if (m.contains("mpegurl")) return MediaKind.HLS;
        if (m.contains("dash+xml")) return MediaKind.DASH;
        // Images, HTML and JSON are never offered, whatever the URL looked like.
        if (m.startsWith("image/") || m.startsWith("text/") || m.startsWith("application/json")) {
            return MediaKind.NONE;
        }
        if (m.startsWith("audio/")) return MediaKind.NONE;
        if (m.startsWith("video/")) return MediaKind.PROGRESSIVE;
        // Some CDNs serve mp4 as application/octet-stream.
        if (m.startsWith("application/octet-stream")) return MediaKind.UNKNOWN;
        return MediaKind.NONE;
    }

    public static String extensionFor(MediaKind kind, String mime, String url) {
        if (mime != null) {
            String m = mime.toLowerCase(Locale.US);
            if (m.contains("webm")) return "webm";
            if (m.contains("matroska")) return "mkv";
            if (m.contains("quicktime")) return "mov";
            if (m.contains("mp4")) return "mp4";
        }
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        if (lower.contains(".webm")) return "webm";
        if (lower.contains(".mkv")) return "mkv";
        if (lower.contains(".mov")) return "mov";
        if (kind == MediaKind.HLS || kind == MediaKind.DASH) return "mp4";
        return "mp4";
    }

    /**
     * Key used to fold several renditions of the same video into one card. Strips the query
     * string and any resolution / bitrate token from the path, so that
     * {@code /clip_720p.mp4} and {@code /clip_1080p.mp4} collapse together.
     */
    public static String groupKey(String url) {
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost() == null ? "" : u.getHost();
            String path = u.getPath() == null ? url : u.getPath();
            String p = path.toLowerCase(Locale.US);
            p = p.replaceAll("[_\\-/](\\d{3,4})p([_\\-./]|$)", "$2");   // _720p
            p = p.replaceAll("[_\\-/](\\d{3,5})k([_\\-./]|$)", "$2");   // _800k
            p = p.replaceAll("[_\\-/](240|360|480|540|720|1080|1440|2160)([_\\-./]|$)", "$2");
            return host + p;
        } catch (Exception e) {
            return url;
        }
    }
}
