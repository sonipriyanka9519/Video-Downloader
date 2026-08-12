package com.ms.webview.detect.site;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Facebook.
 *
 * <p>Ordered by the shared rules, as Instagram is — see {@link #order()}. What is Facebook's own
 * is how its media is found and named: an id buried in a query parameter, a whole file recovered
 * from the byte ranges its player asks for, an image compared by path because every address for
 * it is signed afresh, and a page title that must never be borrowed.
 */
public class FacebookPolicy implements SitePolicy {

    /** The video's id, inside the base64 blob Facebook hangs off every media address. */
    private static final Pattern ASSET_ID =
            Pattern.compile("\"xpv_asset_id\"\\s*:\\s*\"?(\\d+)");

    /** The byte range, as Facebook writes it into the query string. */
    private static final Pattern RANGE_PARAM =
            Pattern.compile("(^|&)(bytestart|byteend)=[^&]*", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean appliesTo(String host) {
        return host.contains("facebook.com") || host.contains("fb.watch")
                || host.contains("fbcdn.net") || host.contains("fbsbx.com");
    }

    /**
     * Newest first, and the shared sheet rules — the same pair Instagram uses.
     *
     * <p>Facebook had rules of its own for a while, and they grew: three groups, a duplicate
     * collapse, a badge driven from a recorded sighting rather than the playing mark. Every one of
     * them was an attempt to place the video on screen correctly <em>without knowing which video
     * it was</em>, and none of them could, because that is not a question about ordering.
     *
     * <p>The shared rules put the playing video first in one line. What was missing was the
     * playing mark being right, and that is fixed where it goes wrong — see
     * {@link #strictHintMatch()}.
     */
    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Facebook's own, and only for what the list holds — each video once, and nothing already
     * watched. The ordering inside {@link FacebookSheetRules} is the shared one, unchanged.
     */
    private final SheetRules sheetRules = new FacebookSheetRules(this);

    @Override
    public SheetRules sheetRules() {
        return sheetRules;
    }

    /**
     * Never. Facebook sets the document title to the caption of whichever post was opened
     * first and leaves it there for the whole session — so borrowing it put that one post's
     * words on every card in the sheet. Falling back to the bare host reads worse and is true.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.NEVER;
    }

    /**
     * False, for now. Facebook's identity is still the weak part here: turning this on before
     * the playing video is reliably recognised would empty the sheet rather than focus it.
     */
    @Override
    public boolean playingOnly() {
        return false;
    }

    /**
     * False. It was set true to copy Instagram and had to come back, and the difference between
     * the two platforms is worth stating because it is not a matter of degree.
     *
     * <p>On Instagram both sides of the comparison say the same word. The scanner reads a
     * shortcode out of the permalink beside the reel; the extractor reads the same shortcode out
     * of the post's own JSON. The id hits, so refusing to look any further costs nothing.
     *
     * <p>On Facebook they frequently cannot. The scanner has only one thing it can ever say —
     * {@code fb:<numeric video id>}, because that is all a Facebook permalink holds — while a
     * feed payload describes most of its clips through a delivery fragment that carries no video
     * id at all, and those are filed under {@code fbn:} or under no name. Two sides naming the
     * same video differently is not a miss that strictness protects against; it is a miss
     * strictness makes permanent. Turned on, nothing on a feed was ever recognised: no badge, and
     * no video placed first.
     *
     * <p>So the weaker signals are reached again — the poster first, which on Facebook is an
     * exact comparison of image paths rather than a guess (see {@link #posterIdentity(String)}),
     * and the running time last, which genuinely is a guess.
     *
     * <p>What made that guess dangerous has been fixed elsewhere and separately. A wrong guess
     * used to reach the download, because the address bar was allowed to name every clip in a
     * feed and a dozen videos merged into one card holding a dozen files. With each video on its
     * own card, a misplaced badge is a misplaced badge: the card the viewer presses is the card
     * they were looking at.
     */
    @Override
    public boolean strictHintMatch() {
        return false;
    }

    /**
     * The video id, recovered from the address.
     *
     * <p>Facebook's renditions of one video share no visible text — {@code .../m366/AQOiSRxx…}
     * and {@code .../m412/AQMSOBXV…} are the 720p and 360p of the same clip — so each was
     * becoming a card of its own. A feed of four videos listed as eight, every second entry a
     * duplicate at another quality, and the video playing as likely to match the 360p copy as
     * the 720p one.
     *
     * <p>The id is there, though: Facebook hangs a base64 blob off every media address in
     * {@code efg}, and inside it {@code xpv_asset_id} is identical across qualities. Read from
     * the address, so it works for renditions overheard on the wire with no JSON to go with
     * them — which on Facebook is most of them.
     *
     * <p>Its own {@code fbx:} namespace on purpose: this groups a ladder, it is not the id the
     * page scanner reads out of a permalink, and the two must not be mistaken for each other.
     */
    @Nullable
    @Override
    public String groupKeyFor(String url) {
        if (url == null || !url.contains("fbcdn.net")) return null;
        try {
            String efg = Uri.parse(url).getQueryParameter("efg");
            if (efg == null || efg.isEmpty()) return null;

            String json = new String(Base64.decode(efg, Base64.DEFAULT), StandardCharsets.UTF_8);
            Matcher m = ASSET_ID.matcher(json);
            return m.find() ? "fbx:" + m.group(1) : null;
        } catch (Exception e) {
            // Malformed or re-encoded: fall back to keying on the address, as before.
            return null;
        }
    }

    /**
     * The picture, not the fetch: an fbcdn image address with its query removed.
     *
     * <p>Facebook stamps a fresh signature, expiry and cache key into every image address it
     * issues, so one thumbnail asked for twice comes back as two strings that share only their
     * path. The path is the file; everything after the {@code ?} is the permission to fetch it
     * this time.
     *
     * <p>This is what lets the video on screen be recognised at all. Facebook plays through
     * MediaSource, so the element's src is a {@code blob:} that matches nothing, and the post id
     * scraped from the markup around it is often absent — which leaves the poster as the one
     * solid tie between the element and a detected video. Compared as whole strings it never
     * matched, and the matcher fell through to picking whichever indexed clip ran closest to the
     * same length. On a feed of same-length clips that is a coin toss, and it is the reason a
     * video plainly playing was listed as one not yet reached, with no badge on it.
     */
    @Override
    public String posterIdentity(String posterUrl) {
        return pathOf(posterUrl);
    }

    /** The path of an address, or the address itself when it has none to take. */
    public static String pathOf(@Nullable String url) {
        if (url == null || url.isEmpty()) return "";
        int cut = url.indexOf('?');
        String withoutQuery = cut < 0 ? url : url.substring(0, cut);

        int scheme = withoutQuery.indexOf("//");
        if (scheme < 0) return withoutQuery;
        int start = withoutQuery.indexOf('/', scheme + 2);
        return start < 0 ? withoutQuery : withoutQuery.substring(start);
    }

    /**
     * The asset id and the file path, both.
     *
     * <p>Facebook does not always send the same identification twice. The asset id lives inside
     * {@code efg}, and {@code efg} is on most media addresses but not all of them; the path is
     * always there but differs between renditions. Each name alone leaves a hole, and the holes
     * are what produced a second card for a video already listed:
     *
     * <ul>
     *   <li>the id alone — a later request for the same file arriving without {@code efg} matches
     *       nothing and starts a card of its own;
     *   <li>the path alone — two renditions of one video have different paths, so the quality
     *       ladder splits into a card per quality, which is what the id was introduced to fix.
     * </ul>
     *
     * <p>Together they have neither hole. Whichever name a request carries leads to the same
     * card, and the card learns the other one, so the next request under either finds it.
     *
     * <p>The path is taken raw, without the query: the signature and expiry Facebook appends
     * change every time the same file is asked for again, and that is precisely the case this is
     * here to stop being mistaken for a different video.
     */
    @Override
    public List<String> groupKeysFor(String url) {
        if (url == null || !url.contains("fbcdn.net")) return Collections.emptyList();

        List<String> keys = new ArrayList<>(2);
        String assetId = groupKeyFor(url);
        if (assetId != null) keys.add(assetId);

        String path = pathOf(url);
        if (!path.isEmpty()) keys.add("fbp:" + path);
        return keys;
    }

    /**
     * The complete mp4 a range request was cut from.
     *
     * <p>The mobile site plays every video through MediaSource. Nothing ever fetches a whole
     * file: each request is {@code …AQNn5q8G….mp4?…&bytestart=0&byteend=524287}, which the
     * engine classifies as a segment and discards, and the element's own src is a {@code blob:}
     * the engine cannot re-fetch. Between the two, a page with a video playing on it detected
     * nothing whatsoever.
     *
     * <p>The range is only a query parameter, and the path still names the file, so dropping the
     * two parameters asks the same CDN for the same mp4 in full. The signature travels in
     * {@code oh}/{@code oe} and covers the path, not the range, so the address stays valid.
     *
     * <p>Surgery on the raw query rather than a rebuilt Uri: these are signed addresses, and
     * re-encoding their values is a good way to invalidate a signature that was computed over
     * the exact bytes Facebook sent.
     */
    @Nullable
    @Override
    public String wholeFileFor(String url) {
        if (url == null || !url.contains("fbcdn.net")) return null;
        try {
            Uri src = Uri.parse(url);
            String path = src.getPath();
            // Only worth recovering when the path names a real file. A slice of anything else
            // is not an mp4 waiting to be asked for properly.
            if (path == null || !path.toLowerCase(Locale.US).endsWith(".mp4")) return null;

            String query = src.getEncodedQuery();
            if (query == null || !RANGE_PARAM.matcher(query).find()) return null;

            String trimmed = RANGE_PARAM.matcher(query).replaceAll("");
            if (trimmed.startsWith("&")) trimmed = trimmed.substring(1);

            return src.buildUpon().encodedQuery(trimmed.isEmpty() ? null : trimmed)
                    .build().toString();
        } catch (Exception e) {
            return null;
        }
    }
}
