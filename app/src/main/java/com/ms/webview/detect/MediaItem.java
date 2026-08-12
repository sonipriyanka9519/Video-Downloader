package com.ms.webview.detect;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.ms.webview.core.Formats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One video as the user perceives it. Several {@link MediaVariant}s (qualities) fold into one
 * item so the sheet shows one card with a quality picker rather than five mystery URLs.
 */
public class MediaItem {

    public final String groupKey;
    public String pageUrl;
    /** Document title of the page this was found on, as a naming fallback. */
    public String pageTitle;
    public String title;
    public String author;
    public String posterUrl;
    /**
     * Headers the poster must be fetched with. Instagram and Facebook CDNs 403 an image
     * request that arrives without a User-Agent and Referer, which is why an unadorned
     * image loader shows a blank preview for reels.
     */
    public final Map<String, String> posterHeaders = new ConcurrentHashMap<>();
    public long durationMs;
    public boolean drmProtected;

    /** True for the video currently playing on screen; the sheet opens on this one. */
    public volatile boolean playing;

    /**
     * Set once this video has been the one on screen, and never unset.
     *
     * <p>It is what keeps a clip already scrolled past from coming back as the next card in the
     * sheet. Watched videos are not removed — one may still be the one worth downloading — they
     * simply sort behind everything not yet seen.
     */
    public volatile boolean watched;

    /** Whether the best rendition is taller than it is wide, i.e. a reel or a story. */
    public synchronized boolean portrait() {
        for (MediaVariant v : variants()) {
            if (v.width > 0 && v.height > 0) return v.portrait();
        }
        return false;
    }
    /**
     * A JPEG data URI grabbed from the playing element, refreshed every couple of seconds.
     * Preferred over the poster because it shows the frame the user is actually looking at.
     */
    public volatile String liveFrame;
    /** A frame decoded from the video itself, used when the platform supplied no poster. */
    public volatile String localThumbnail;
    /**
     * Set once any variant has been proved to be a real, playable video. Its siblings then only
     * need a cheap probe — they came from the same manifest or the same quality list, so one
     * decode vouches for the set. Without this, strict verification quietly discarded every
     * alternative quality that the budget could not afford to open.
     */
    public volatile boolean videoConfirmed;

    /**
     * Set from the platform's policy when the card is created, because a card cannot reach the
     * policy itself. See {@link com.ms.webview.detect.site.SitePolicy#preferProgressive()}.
     */
    public boolean preferProgressive;

    /**
     * True when the site itself described this video — a player record naming the file, its
     * length and its poster — rather than the detector inferring it from a request it overheard.
     *
     * <p>The difference matters where a player is handed one clip at a time: what the site
     * describes is what it is showing, while what the network reveals includes whatever is being
     * fetched ahead. See {@link com.ms.webview.detect.site.SitePolicy#latestDeclaredOnly()}.
     */
    public volatile boolean declared;

    /** The rung not worth offering when anything better exists. */
    private static final int THROWAWAY_RUNG = 240;

    public final long discoveredAt = System.currentTimeMillis();

    /**
     * When this video was last recognised as the one on screen, or 0 if it never has been.
     *
     * <p>Kept separately from {@link #playing} because the two answer different questions, and
     * only one of them can be answered reliably. Whether a video is playing <em>right now</em>
     * depends on the current scan tying the element to it, which on a MediaSource player is
     * intermittent. Whether it was the last thing seen does not: once a scan succeeds the fact is
     * recorded, and no later miss can unmake it.
     */
    public volatile long seenAt;

    /**
     * Whether the sheet should present this as the video being watched — the NOW PLAYING badge,
     * and the card the sheet opens on.
     *
     * <p>Set by the platform's {@link com.ms.webview.detect.site.SheetRules} each time the list
     * is arranged, because what counts as "the one being watched" is the platform's own question
     * and it already had to answer it to decide the order. Most platforms answer it with
     * {@link #playing} and nothing changes. A platform whose recognition is intermittent answers
     * it with the last sighting instead, so a card does not lose its badge every time a scan
     * misses — which is how a video plainly on screen came to sit at the front of the sheet with
     * nothing marking it as current.
     */
    public volatile boolean current;

    /**
     * The most recent thing known about this video: when it was last watched, or failing that
     * when it was found.
     *
     * <p>The one ordering signal on a scrolling feed that is both meaningful and always
     * available. The clip on screen has just been seen, so it leads. A clip fetched ahead has
     * never been seen but was found a moment ago, so it follows. A clip watched and left behind
     * has an older timestamp than both, so it sinks — without needing to be marked watched, and
     * without any of it resting on the current scan having succeeded.
     */
    public long lastActiveAt() {
        return Math.max(seenAt, discoveredAt);
    }

    private final List<MediaVariant> variants = new ArrayList<>();

    public MediaItem(String groupKey) {
        this.groupKey = groupKey;
    }

    /**
     * Every variant worth using. Master playlists are excluded in favour of the renditions they
     * expanded into, and where a single quality is offered both as a progressive file and as
     * HLS, the progressive one wins — it downloads faster and needs no remux.
     *
     * <p>This is the engine's list, not the sheet's. What is opened to be decoded, what is
     * registered as belonging to this card, and what a download resolves to all come from here,
     * so nothing may be removed for the sake of appearance. See {@link #qualities()} for the
     * list the grid is built from.
     */
    public synchronized List<MediaVariant> variants() {
        Map<Integer, MediaVariant> byRung = new LinkedHashMap<>();
        List<MediaVariant> unkeyed = new ArrayList<>();

        // Where the platform asks for it, a real file wins outright over the ladder beside it:
        // one fetch, no segments to stitch, nothing to remux. Only applied when such a file is
        // actually present, so a stream-only video is untouched. See preferProgressive.
        boolean fileOnly = preferProgressive && hasProgressive();

        for (MediaVariant v : variants) {
            if (v.hidden || v.kind == MediaKind.NONE) continue;
            if (fileOnly && v.kind != MediaKind.PROGRESSIVE) continue;
            // Keyed by rung rather than raw height, so 720x1274 and 720x1254 are recognised as
            // two encodes of one quality instead of two qualities with contradictory sizes.
            int rung = v.qualityRung();
            if (rung <= 0) {
                unkeyed.add(v);
                continue;
            }
            MediaVariant existing = byRung.get(rung);
            if (existing == null || preferOver(v, existing)) byRung.put(rung, v);
        }

        List<MediaVariant> result = new ArrayList<>(byRung.values());
        result.addAll(filterUnkeyed(unkeyed, byRung.values()));
        Collections.sort(result, (a, b) -> Integer.compare(b.rank(), a.rank()));
        return result;
    }

    /**
     * The ladder as the sheet offers it: {@link #variants()} without 240p, which almost every
     * platform offers and almost nobody chooses, and which on a phone looks worse than the
     * preview it was picked from.
     *
     * <p>A display rule, and it has to stay one. Applied inside {@link #variants()} it also
     * removed the rendition from the list the engine opens for decoding — so on a platform whose
     * low rung is the one that can actually be fetched, the only openable file was hidden from
     * the decoder, nothing was ever confirmed as video, and the card was detected but never
     * offerable. Facebook, whose whole file is recovered from the stream the player was reading,
     * is exactly that platform.
     *
     * <p>Never the last thing standing. Where 240p is all a video has, it stays: an unwatchable
     * download is still better than a card with nothing to press.
     */
    public synchronized List<MediaVariant> qualities() {
        List<MediaVariant> all = variants();
        if (all.size() <= 1) return all;

        List<MediaVariant> kept = new ArrayList<>(all.size());
        for (MediaVariant v : all) {
            if (v.qualityRung() == THROWAWAY_RUNG) continue;
            if (isUnlabelled(v)) continue;
            kept.add(v);
        }
        return kept.isEmpty() ? all : kept;
    }

    /**
     * A variant the grid can only call "Original" — no dimensions and no bitrate to name it by.
     *
     * <p>Beside real rungs it is unreadable: the viewer is asked to choose between 720p, 360p and
     * a tile that says nothing about what it is or how it compares. In practice it is nearly
     * always one of the rungs already listed, reached by a second address that has not been
     * measured yet.
     *
     * <p>Dropped from the grid only, and only while something better is there to choose. It stays
     * in {@link #variants()}, so it can still be decoded, owned and downloaded, and it comes back
     * to the grid the moment it is the only thing left.
     */
    private static boolean isUnlabelled(MediaVariant v) {
        return v.qualityRung() <= 0 && v.bandwidth <= 0;
    }

    /**
     * Decides which dimensionless variants deserve their own "Original" tile.
     *
     * <p>Two reasons one usually does not. It may be a rung we already list, reached by a second
     * URL — the same file fetched by the page as well as named by the extractor — which shows up
     * as an "Original" weighing exactly what the 720p weighs. And one still being inspected will
     * very likely resolve into a rung shortly, folding away and rearranging the grid under the
     * user's finger, which is what made tapping a quality look like it merged the tiles.
     *
     * <p>When nothing at all has dimensions they are all kept, so the grid is never empty.
     */
    /** Whether a self-contained file is among the variants worth offering. */
    private boolean hasProgressive() {
        for (MediaVariant v : variants) {
            if (v.hidden || v.kind != MediaKind.PROGRESSIVE) continue;
            // Only a file already shown to be real. Dropping a working ladder in favour of a
            // candidate that has not been opened yet would trade a usable offer for a guess.
            if (v.verified || v.inspected || v.sizeBytes > 0) return true;
        }
        return false;
    }

    private List<MediaVariant> filterUnkeyed(List<MediaVariant> unkeyed,
                                             Collection<MediaVariant> known) {
        if (unkeyed.isEmpty() || known.isEmpty()) return dedupeBySize(unkeyed);

        List<MediaVariant> kept = new ArrayList<>();
        for (MediaVariant v : unkeyed) {
            if (!v.inspected) continue;
            if (matchesKnownSize(v, known)) continue;
            kept.add(v);
        }
        return dedupeBySize(kept);
    }

    private boolean matchesKnownSize(MediaVariant candidate, Collection<MediaVariant> known) {
        long candidateSize = candidate.sizeFor(durationMs);
        if (candidateSize <= 0) return false;

        for (MediaVariant v : known) {
            long knownSize = v.sizeFor(durationMs);
            if (knownSize <= 0) continue;
            // Adjacent rungs differ by a factor, never by a rounding error, so a near-identical
            // byte count means the same encode rather than a neighbouring quality.
            long tolerance = Math.max(2048, Math.round(knownSize * 0.02));
            if (Math.abs(knownSize - candidateSize) <= tolerance) return true;
        }
        return false;
    }

    /**
     * Variants with no resolution and an identical byte count are the same rendition reached by
     * different URLs. Listing four of them as four qualities is worse than useless.
     */
    private static List<MediaVariant> dedupeBySize(List<MediaVariant> variants) {
        List<MediaVariant> kept = new ArrayList<>(variants.size());
        Map<String, MediaVariant> bySize = new LinkedHashMap<>();
        for (MediaVariant v : variants) {
            if (v.sizeBytes <= 0) {
                kept.add(v);
                continue;
            }
            String key = v.kind.name() + ':' + v.sizeBytes;
            if (!bySize.containsKey(key)) bySize.put(key, v);
        }
        kept.addAll(bySize.values());
        return kept;
    }

    private static boolean preferOver(MediaVariant candidate, MediaVariant existing) {
        boolean candidateProgressive = candidate.kind == MediaKind.PROGRESSIVE;
        boolean existingProgressive = existing.kind == MediaKind.PROGRESSIVE;
        if (candidateProgressive != existingProgressive) return candidateProgressive;
        // Same delivery: keep whichever we have actually measured.
        if (candidate.sizeBytes > 0 != existing.sizeBytes > 0) return candidate.sizeBytes > 0;
        return candidate.sizeBytes > existing.sizeBytes;
    }

    public synchronized MediaVariant variantFor(String url) {
        for (MediaVariant v : variants) {
            if (v.url.equals(url)) return v;
        }
        return null;
    }

    public synchronized MediaVariant addOrGet(String url, MediaKind kind) {
        MediaVariant existing = variantFor(url);
        if (existing != null) {
            // A DOM or JSON sighting may upgrade an UNKNOWN classified purely from the URL shape.
            if (existing.kind == MediaKind.UNKNOWN && kind != MediaKind.UNKNOWN) existing.kind = kind;
            return existing;
        }
        MediaVariant v = new MediaVariant(url, kind);
        variants.add(v);
        sortVariants();
        return v;
    }

    public synchronized void sortVariants() {
        Collections.sort(variants, (a, b) -> Integer.compare(b.rank(), a.rank()));
    }

    /**
     * The variant to pre-select.
     *
     * <p>Biased towards reliability over raw resolution: a progressive file is one HTTP GET,
     * while a playlist rendition has to be assembled from segments and remuxed, with more ways
     * to end up with something that will not play. The user can still pick any rung from the
     * quality grid — this only decides the default.
     */
    @Nullable
    public synchronized MediaVariant bestDownloadable() {
        if (drmProtected) return null;

        MediaVariant decodedFile = null;
        MediaVariant anyFile = null;
        MediaVariant anything = null;
        for (MediaVariant v : variants()) {
            if (!v.kind.downloadable()) continue;
            if (anything == null) anything = v;
            boolean isFile = v.kind == MediaKind.PROGRESSIVE;
            if (isFile && anyFile == null) anyFile = v;
            // One we actually opened is the safest thing to hand the downloader.
            if (isFile && v.decoded && decodedFile == null) decodedFile = v;
        }
        if (decodedFile != null) return decodedFile;
        return anyFile != null ? anyFile : anything;
    }

    public synchronized boolean hasDownloadable() {
        return bestDownloadable() != null;
    }

    public synchronized boolean hasVerifiedVariant() {
        for (MediaVariant v : variants) {
            if (!v.hidden && v.verified && v.kind.downloadable()) return true;
        }
        return false;
    }

    /**
     * Accepts a video the platform described in full, without waiting for it to be opened.
     * Only ever acts on an item already marked {@link #declared} — see
     * {@link com.ms.webview.detect.site.SitePolicy#trustDeclaredMedia()} for when that is set.
     *
     * <p>Safe to call as often as you like, and it needs to be. The record's parts do not always
     * arrive together: an address can turn up in one payload and its poster or running time in
     * the next, and a test run once at arrival would have judged the record incomplete and never
     * looked again. That is a video that plays perfectly well being reported as one that "could
     * not be opened for download" — which is the whole failure this is here to stop.
     *
     * @return whether anything changed, so the caller knows to republish.
     */
    public synchronized boolean trustDeclared() {
        if (!declared || durationMs <= 0 || TextUtils.isEmpty(thumbnail())) return false;
        boolean changed = false;
        for (MediaVariant v : variants) {
            if (v.hidden || v.verified || !v.kind.downloadable()) continue;
            v.verified = true;
            changed = true;
        }
        if (changed) videoConfirmed = true;
        return changed;
    }

    /**
     * Whether this belongs in the sheet.
     *
     * <p>Three conditions, each earned the hard way:
     *
     * <ul>
     *   <li>{@link #videoConfirmed} — some variant was opened and decoded as real video;
     *   <li>a known duration, which a fragment or a broken stream never has;
     *   <li>a preview to show for it.
     * </ul>
     *
     * <p>Decoding is the load-bearing condition. Accepting an entry on the platform's word was
     * tried and produced exactly the cards that show no preview and then fail to download —
     * unsurprisingly, since a file we cannot open is usually one we cannot use. If a real video
     * is being hidden, the fix belongs in the decoder, not in this test.
     *
     * <p>DRM items are the exception: shown as protected, so the sheet explains itself rather
     * than looking empty.
     */
    public synchronized boolean presentable() {
        if (drmProtected) return true;
        return videoConfirmed
                && hasVerifiedVariant()
                && durationMs > 0
                && !TextUtils.isEmpty(thumbnail());
    }

    /**
     * Best available preview, most current first: the frame captured while the user was
     * watching, then one decoded from the file, then the platform's poster.
     */
    @Nullable
    public String thumbnail() {
        if (!TextUtils.isEmpty(liveFrame)) return liveFrame;
        if (!TextUtils.isEmpty(localThumbnail)) return localThumbnail;
        return posterUrl;
    }

    /**
     * A poster a download can keep. Deliberately not the live frame: that is a base64 data URI,
     * far too large to travel in an Intent or sit in a database row.
     */
    @Nullable
    public String persistableThumbnail() {
        return TextUtils.isEmpty(localThumbnail) ? posterUrl : localThumbnail;
    }

    /**
     * Best readable name, in descending order of how much it actually says about the video.
     * The CDN file name comes near the end because it is almost always an opaque token, and
     * the page title beats it whenever there is one.
     */
    public String displayTitle() {
        if (!TextUtils.isEmpty(title)) return title;
        if (!TextUtils.isEmpty(author)) return author;
        if (!TextUtils.isEmpty(pageTitle)) return pageTitle;
        String seg = Formats.lastPathSegment(firstUrl());
        if (!TextUtils.isEmpty(seg) && Formats.isMeaningfulName(seg)) return seg;
        return Formats.hostOf(pageUrl);
    }

    public synchronized String firstUrl() {
        List<MediaVariant> visible = variants();
        if (!visible.isEmpty()) return visible.get(0).url;
        return variants.isEmpty() ? "" : variants.get(0).url;
    }

    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (!TextUtils.isEmpty(author)) sb.append('@').append(author);
        if (durationMs > 0) {
            sb.append(sb.length() > 0 ? " · " : "").append(Formats.duration(durationMs));
        }
        int qualities = qualities().size();
        if (qualities > 1) {
            sb.append(sb.length() > 0 ? " · " : "").append(qualities).append(" qualities");
        }
        // The site the user is on, not the CDN the file happens to sit on: nobody recognises
        // "instagram.fstv5-1.fna.fbcdn.net" as the page they are looking at.
        String host = Formats.hostOf(pageUrl);
        if (host.startsWith("www.")) host = host.substring(4);
        if (!host.isEmpty()) sb.append(sb.length() > 0 ? " · " : "").append(host);
        return sb.toString();
    }
}
