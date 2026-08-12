package com.ms.webview.detect;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.ms.webview.core.Formats;
import com.ms.webview.core.Http;
import com.ms.webview.detect.extract.FoundMedia;
import com.ms.webview.detect.page.PageResolver;
import com.ms.webview.detect.page.PageResolvers;
import com.ms.webview.detect.site.SheetRules;
import com.ms.webview.detect.site.SitePolicies;
import com.ms.webview.detect.site.SitePolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Collects every media sighting from every detection layer for the current page, folds
 * duplicates and renditions together, verifies them, and exposes the finished ones to the UI.
 *
 * <p>Detection is deliberately a pipeline rather than a firehose. A URL is only published once
 * it has been fetched successfully and had its real dimensions read, because a card the user
 * can tap must be one that will actually download.
 *
 * <p>Written to from the WebView network thread, the JS bridge thread and two worker pools;
 * read from the main thread. All mutation is guarded by {@code lock}.
 */
public class MediaRegistry {

    /**
     * One logcat tag for the whole detection pipeline. Filter on it to see, per URL, what was
     * probed, what decoded, and what was rejected — which is the only practical way to work out
     * why a particular site yields nothing.
     */
    public static final String DIAG = "VideoDetect";

    /** Feeds beyond this many distinct videos are almost certainly runaway detection. */
    private static final int MAX_ITEMS = 60;
    /** How close two durations must be to be considered the same video. */
    private static final long DURATION_TOLERANCE_MS = 900;

    /**
     * How much closer one running time must be than the next before it settles which video is
     * playing. Two clips within a fifth of a second of each other are not told apart this way.
     */
    private static final long CLEAR_WINNER_MS = 200;

    /** Last line printed by {@link #match}, so a repeat of it is not printed again. */
    private String lastMatchLog = "";
    /**
     * A news listing page can yield dozens of videos with several renditions each. Probing all
     * of them would mean hundreds of requests to sites we are only browsing.
     */
    private static final int PROBE_BUDGET = 150;
    /**
     * Reading metadata opens the video itself, so it is far more expensive than a probe. This
     * caps how much of a page we are willing to pay for.
     */
    private static final int METADATA_BUDGET = 45;
    /**
     * Decoding a locally downloaded head costs real bandwidth, so it is reserved for the
     * variants the retriever could not open on its own.
     */
    private static final int DOWNLOAD_FALLBACKS = 12;
    private static final long METADATA_TIMEOUT_SECONDS = 25;

    private final Object lock = new Object();
    private final LinkedHashMap<String, MediaItem> items = new LinkedHashMap<>();
    /**
     * Which item owns a given URL. Needed because an HLS rendition's URL rarely shares a group
     * key with the master that produced it, and without this the player's own fetch of that
     * rendition would spawn a duplicate card.
     */
    private final Map<String, MediaItem> owners = new HashMap<>();
    /** Platform post ids, so every quality of one post lands on one card. */
    private final Map<String, MediaItem> hints = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    private final Set<String> metadataQueued = new HashSet<>();
    private final Set<String> pagesResolved = new HashSet<>();

    private final MutableLiveData<List<MediaItem>> live = new MutableLiveData<>(Collections.emptyList());
    private final MutableLiveData<Integer> unresolvedLive = new MutableLiveData<>(0);
    private final ExecutorService probePool = Executors.newFixedThreadPool(3);
    /** Two only: each task decodes video and holds a bitmap. */
    private final ExecutorService metadataPool = Executors.newFixedThreadPool(2);
    /** Expendable threads for the reads themselves, so a hung one is abandoned, not queued behind. */
    private final ExecutorService readerPool = Executors.newCachedThreadPool();
    private final Prober prober = new Prober();
    private final HlsResolver hlsResolver = new HlsResolver();
    private final DashResolver dashResolver = new DashResolver();
    private final MetadataReader metadataReader;

    private String pageUrl = "";
    private String pageTitle = "";
    /** Which page {@link #pageTitle} actually describes. See {@link #setPageTitle}. */
    private String pageTitleUrl = "";
    private volatile String userAgent = Http.DEFAULT_UA;
    private boolean usesMediaSource;
    private int probesUsed;
    private int metadataUsed;
    private int downloadFallbacksUsed;

    public MediaRegistry(Context context) {
        this.metadataReader = new MetadataReader(context);
    }

    public LiveData<List<MediaItem>> live() {
        return live;
    }

    public String pageUrl() {
        synchronized (lock) {
            return pageUrl;
        }
    }

    /** True once the page has been seen creating a MediaSource, i.e. it is adaptive-only. */
    public boolean usesMediaSource() {
        synchronized (lock) {
            return usesMediaSource;
        }
    }

    public void noteMediaSource() {
        synchronized (lock) {
            usesMediaSource = true;
        }
    }

    /**
     * Called on a real navigation only — not on SPA history changes, since Instagram-style
     * feeds swap the URL constantly while the already-detected reels stay on screen.
     */
    public void startPage(String url) {
        synchronized (lock) {
            pageUrl = url == null ? "" : url;
            items.clear();
            owners.clear();
            hints.clear();
            inFlight.clear();
            metadataQueued.clear();
            pagesResolved.clear();
            usesMediaSource = false;
            probesUsed = 0;
            metadataUsed = 0;
            downloadFallbacksUsed = 0;
        }
        publish();
    }

    /**
     * Follows a single-page-app route change.
     *
     * <p>Only a change of <em>host</em> discards what has been found. Clearing on a path change
     * seemed right — the videos belong to the view you left — but a reels feed rewrites its path
     * to the current clip on every scroll, so that emptied the registry each time you moved on.
     * Worse, those platforms prefetch several clips in one response: once discarded, that data
     * is never sent again and those reels become permanently undetectable.
     *
     * <p>Staying on one site therefore accumulates, which is what makes scrolling a feed keep
     * turning up videos. The one being watched still sorts to the front, and the item cap evicts
     * the oldest rather than refusing anything new.
     */
    public void updatePageUrl(String url) {
        if (TextUtils.isEmpty(url)) return;

        boolean leftTheSite;
        boolean newView;
        boolean restart;
        synchronized (lock) {
            leftTheSite = !sameHost(pageUrl, url);
            newView = !TextUtils.equals(pageUrl, url);
            // Whether a new route on the same site is a fresh start is the platform's call.
            // On a feed it is not — the path follows the scroll. On a grid it is: the viewer
            // has opened one thing, and the rest of the grid is no longer what they are
            // looking at.
            //
            // Judged on the path alone, never the query string. A site routinely rewrites its
            // own address after load — dropping a redirect_to, a login-wall marker, a tracking
            // tag — with the page unchanged beneath it. Counting that as a new route wiped a
            // feed the instant it was detected and rebuilt it from nothing, over and over: the
            // sheet never held still long enough to show anything.
            restart = !leftTheSite && !sameView(pageUrl, url)
                    && SitePolicies.forHost(Formats.hostOf(url)).resetOnRouteChange();
            pageUrl = url;
            // A new route on the same site earns a fresh allowance, so a long scroll does not
            // run out of budget and quietly stop inspecting.
            if (newView && !leftTheSite) {
                probesUsed = 0;
                metadataUsed = 0;
                downloadFallbacksUsed = 0;
            }
        }
        if (leftTheSite || restart) startPage(url);
    }

    /**
     * Whether the document title in hand may name a video found at {@code url}.
     *
     * <p>Which it depends on is the platform's own business, so the platform decides — see
     * {@link SitePolicy#pageTitleUse()}. Caller must hold {@code lock}.
     */
    private boolean titleBelongsTo(String url) {
        if (TextUtils.isEmpty(pageTitleUrl)) return false;
        switch (SitePolicies.forHost(Formats.hostOf(pageTitleUrl)).pageTitleUse()) {
            case PER_URL:
                return TextUtils.equals(url, pageTitleUrl);
            case PER_PAGE:
                return sameHost(pageTitleUrl, url);
            case NEVER:
            default:
                return false;
        }
    }

    private static boolean sameHost(String a, String b) {
        if (TextUtils.isEmpty(a)) return false;
        try {
            return TextUtils.equals(Uri.parse(a).getHost(), Uri.parse(b).getHost());
        } catch (Exception e) {
            return TextUtils.equals(a, b);
        }
    }

    /**
     * Whether two addresses show the same thing.
     *
     * <p>The platform's own name for the view where it has one, the path everywhere else — so a
     * site that opens its videos as a parameter is followed by the video rather than by the shape
     * of its address. Nothing changes for a site that names no route: the answer is
     * {@link #samePath} exactly as before.
     */
    private static boolean sameView(String a, String b) {
        String ka = SitePolicies.routeKey(a);
        String kb = SitePolicies.routeKey(b);
        if (ka != null || kb != null) return TextUtils.equals(ka, kb);
        return samePath(a, b);
    }

    /** Same host and same path — a change confined to the query string does not count. */
    private static boolean samePath(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return false;
        try {
            Uri ua = Uri.parse(a);
            Uri ub = Uri.parse(b);
            return TextUtils.equals(ua.getHost(), ub.getHost())
                    && TextUtils.equals(ua.getPath(), ub.getPath());
        } catch (Exception e) {
            return TextUtils.equals(a, b);
        }
    }

    /**
     * The document title, used to name videos the platform did not name itself.
     *
     * <p>The title is recorded against the page it describes, and that pairing is the whole
     * point. On a reels feed the address changes the moment you scroll, but the document title
     * follows a beat later — so a video detected in between used to be stamped with the title
     * of the clip before it, and the title that finally arrived went on the clip after. Every
     * card was one behind.
     *
     * <p>So a title is only ever applied to videos found on the page it came from: back-filled
     * onto the ones already waiting for it, and never handed to a video from somewhere else.
     */
    public void setPageTitle(String title) {
        synchronized (lock) {
            pageTitle = TextUtils.isEmpty(title) ? "" : title;
            pageTitleUrl = pageUrl;
            if (pageTitle.isEmpty()) return;

            for (MediaItem item : items.values()) {
                if (TextUtils.isEmpty(item.pageTitle) && titleBelongsTo(item.pageUrl)) {
                    item.pageTitle = pageTitle;
                }
            }
        }
    }

    public void clear() {
        startPage(pageUrl());
    }

    // ------------------------------------------------------------------ layer 1

    /** A URL the WebView requested, with the headers it used. */
    public void offerNetwork(String url, MediaKind kind, Map<String, String> headers) {
        if (kind == MediaKind.NONE) return;
        MediaItem item;
        MediaVariant v;
        synchronized (lock) {
            // The page is not a video on it. A watch address like /video/x9hrf9g looks enough
            // like media to be offered, and then sits in the sheet as a card that probes back
            // as text/html and can never be opened.
            if (url.equals(pageUrl)) return;
            if (kind == MediaKind.SEGMENT) {
                // A slice is still not offerable. But on a platform that plays only through
                // MediaSource, slices are the only requests there are, and discarding them
                // means detecting nothing on a page with a video playing on it. Whether the
                // whole file can be recovered from the slice's address is that platform's
                // call, kept in its own file.
                String whole = SitePolicies.forHost(Formats.hostOf(pageUrl)).wholeFileFor(url);
                if (whole == null) return;
                url = whole;
                kind = UrlClassifier.classify(url);
                if (kind == MediaKind.NONE || kind == MediaKind.SEGMENT) return;
            }
            item = itemFor(url, null);
            if (item == null) return;
            v = item.addOrGet(url, kind);
            owners.put(url, item);
            if (headers != null) v.headers.putAll(headers);
        }
        enrich(item, v);
    }

    // ------------------------------------------------------------------ layer 2

    /** A sighting from the injected DOM scanner, which also carries the nice metadata. */
    public void offerDom(String url, MediaKind kind, @Nullable String poster, long durationMs,
                         int width, int height, @Nullable String title, @Nullable String hint,
                         @Nullable Map<String, String> headers) {
        if (kind == MediaKind.NONE || kind == MediaKind.SEGMENT) return;
        MediaItem item;
        MediaVariant v;
        synchronized (lock) {
            item = itemFor(url, hint);
            if (item == null) return;
            v = item.addOrGet(url, kind);
            owners.put(url, item);
            mergeHeaders(v, headers);
            if (width > 0 && v.width <= 0) v.width = width;
            if (height > 0 && v.height <= 0) v.height = height;
            if (!TextUtils.isEmpty(poster) && TextUtils.isEmpty(item.posterUrl)) {
                setPoster(item, poster, headers);
            }
            if (durationMs > 0 && item.durationMs <= 0) item.durationMs = durationMs;
            if (!TextUtils.isEmpty(title) && TextUtils.isEmpty(item.title)) item.title = title;
            item.sortVariants();
        }
        enrich(item, v);
    }

    // ------------------------------------------------------------------ layer 3

    /** A video recovered from a platform's own JSON by a site extractor. */
    public void offerExtracted(FoundMedia media, @Nullable Map<String, String> headers) {
        if (media == null || !media.valid()) return;

        if (media.hasInlineManifest()) {
            offerInlineManifest(media, headers);
            return;
        }

        MediaItem item;
        MediaVariant v;
        synchronized (lock) {
            item = itemFor(media.url, media.groupHint);
            if (item == null) return;
            v = item.addOrGet(media.url, media.kind);
            owners.put(media.url, item);
            if (media.groupHint != null) hints.put(media.groupHint, item);

            mergeHeaders(v, headers);
            if (media.width > 0) v.width = media.width;
            if (media.height > 0) v.height = media.height;
            if (media.bitrate > 0) v.bandwidth = media.bitrate;
            if (!TextUtils.isEmpty(media.audioUrl)) v.audioUrl = media.audioUrl;
            if (v.mime == null) v.mime = "video/mp4";

            if (!TextUtils.isEmpty(media.thumbnail) && TextUtils.isEmpty(item.posterUrl)) {
                setPoster(item, media.thumbnail, headers);
            }
            if (!TextUtils.isEmpty(media.title) && TextUtils.isEmpty(item.title)) {
                item.title = media.title;
            }
            if (!TextUtils.isEmpty(media.author) && TextUtils.isEmpty(item.author)) {
                item.author = media.author;
            }
            if (media.durationMs > 0 && item.durationMs <= 0) item.durationMs = media.durationMs;

            // Described by the site rather than inferred from traffic. Recorded because on a
            // one-clip-at-a-time player that is the difference between the video being shown and
            // one merely being fetched ahead of it — and because where the platform's record is
            // trusted, it is what lets the video be offered without a round trip and a decode.
            //
            // The mark goes on as soon as the address arrives; whether the record is complete
            // enough to act on is asked separately, and asked again later, because the poster and
            // the running time do not always arrive in the same payload as the address.
            if (v.kind.downloadable()
                    && SitePolicies.forHost(Formats.hostOf(pageUrl)).trustDeclaredMedia()) {
                item.declared = true;
                item.trustDeclared();
            }

            item.sortVariants();
        }
        enrich(item, v);
    }

    /**
     * A DASH manifest that arrived as XML inside the page's own JSON. Nothing to fetch: the
     * quality ladder is parsed straight out of it.
     */
    private void offerInlineManifest(FoundMedia media, @Nullable Map<String, String> headers) {
        MediaItem item;
        synchronized (lock) {
            item = itemFor(media.url, media.groupHint);
            if (item == null) return;
            if (media.groupHint != null) hints.put(media.groupHint, item);
            if (!TextUtils.isEmpty(media.thumbnail) && TextUtils.isEmpty(item.posterUrl)) {
                setPoster(item, media.thumbnail, headers);
            }
            if (!TextUtils.isEmpty(media.title) && TextUtils.isEmpty(item.title)) {
                item.title = media.title;
            }
            if (!TextUtils.isEmpty(media.author) && TextUtils.isEmpty(item.author)) {
                item.author = media.author;
            }
            if (media.durationMs > 0 && item.durationMs <= 0) item.durationMs = media.durationMs;
            if (!metadataQueued.add(media.url)) return;   // already expanded this manifest
        }

        final MediaItem target = item;
        probePool.execute(() -> {
            // Relative BaseURLs resolve against the page the manifest was embedded in.
            dashResolver.resolveInline(target, media.inlineManifest, pageUrl(), headers);
            synchronized (lock) {
                for (MediaVariant v : target.variants()) owners.put(v.url, target);
            }
            scheduleMetadata(target);
            publish();
        });
    }

    // ------------------------------------------------------------ page resolvers

    /** The WebView's user agent, so out-of-band requests look like the browser that made them. */
    public void setUserAgent(String userAgent) {
        if (!TextUtils.isEmpty(userAgent)) this.userAgent = userAgent;
    }

    /**
     * Asks the platform directly what this page holds, for the sites where overhearing their
     * traffic is not enough. Safe to call repeatedly; each URL is resolved once.
     */
    public void resolvePage(String url) {
        if (TextUtils.isEmpty(url)) return;
        List<PageResolver> resolvers = PageResolvers.forUrl(url);
        if (resolvers.isEmpty()) return;

        synchronized (lock) {
            if (!pagesResolved.add(url)) return;
        }

        final String agent = userAgent;
        probePool.execute(() -> {
            for (PageResolver resolver : resolvers) {
                if (alreadyKnown(resolver.hintFor(url))) {
                    Log.i(DIAG, "page resolver skipped, already detected: " + url);
                    continue;
                }
                try {
                    List<FoundMedia> found = resolver.resolve(url, agent);
                    Log.i(DIAG, "page resolver " + resolver.getClass().getSimpleName()
                            + " -> " + found.size() + " for " + url);
                    Map<String, String> headers = outboundHeaders(url, agent);
                    for (FoundMedia media : found) offerExtracted(media, headers);
                } catch (Exception e) {
                    Log.w(DIAG, "page resolver failed for " + url, e);
                }
            }
        });
    }

    /** Whether this post has already been detected from the page's own traffic. */
    private boolean alreadyKnown(@Nullable String hint) {
        if (hint == null) return false;
        synchronized (lock) {
            MediaItem existing = hints.get(hint);
            return existing != null && existing.presentable();
        }
    }

    /** Headers a CDN will accept for media discovered out of band. */
    private static Map<String, String> outboundHeaders(String pageUrl, String agent) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", agent);
        String origin = Http.originOf(pageUrl);
        if (origin != null) headers.put("Referer", origin);
        return headers;
    }

    // ------------------------------------------------- current video and frames

    /**
     * Marks which detected video is the one playing on screen, and takes the name the page
     * gives it.
     *
     * <p>The name arrives here and not only through {@code offerDom} because that route is
     * keyed on the element's src, and a video played through MediaSource has a {@code blob:}
     * src the engine discards — so on those sites nothing the page says about the video ever
     * reached its card, and it showed the bare host instead.
     */
    public void notePlaying(@Nullable String hint, String src, @Nullable String poster,
                            long durationMs, @Nullable String title) {
        boolean changed = false;
        MediaItem adopted = null;
        MediaVariant adoptedVariant = null;
        synchronized (lock) {
            // Where the platform asks for it, the playing element's own source is a detected
            // video. This is what lets a feed whose playing address matches nothing the passive
            // layers saw still show the video on screen — see adoptPlayingSource.
            if (!TextUtils.isEmpty(src) && !src.startsWith("blob:")
                    && SitePolicies.forHost(Formats.hostOf(pageUrl)).adoptPlayingSource()) {
                MediaKind kind = UrlClassifier.classify(src);
                if (kind != MediaKind.NONE && kind != MediaKind.SEGMENT) {
                    adopted = itemFor(src, hint);
                    if (adopted != null) {
                        adoptedVariant = adopted.addOrGet(src, kind);
                        owners.put(src, adopted);
                        if (adoptedVariant.headers.isEmpty()) {
                            adoptedVariant.headers.putAll(outboundHeaders(pageUrl, userAgent));
                        }
                        if (!TextUtils.isEmpty(poster) && TextUtils.isEmpty(adopted.posterUrl)) {
                            setPoster(adopted, poster, adoptedVariant.headers);
                        }
                        if (durationMs > 0 && adopted.durationMs <= 0) {
                            adopted.durationMs = durationMs;
                        }
                    }
                }
            }

            MediaItem match = adopted != null ? adopted : match(hint, src, poster, durationMs);
            // Only ever fills a gap. A name the extractor read out of the post's own JSON is
            // better evidence than anything scraped off the page around it, and must not be
            // overwritten by it.
            if (match != null && !TextUtils.isEmpty(title) && TextUtils.isEmpty(match.title)) {
                match.title = title;
                changed = true;
            }
            for (MediaItem item : items.values()) {
                boolean playing = item == match;
                if (item.playing != playing) {
                    item.playing = playing;
                    changed = true;
                }
            }
            // Reaching the screen is what counts as watched, and it never wears off.
            if (match != null) {
                match.seenAt = System.currentTimeMillis();
                if (!match.watched) {
                    match.watched = true;
                    changed = true;
                }
            }
        }
        // Probe the adopted source outside the lock. Idempotent: a variant already in flight or
        // already probed is left alone, so the repeated calls this method makes cost nothing.
        if (adopted != null && adoptedVariant != null) enrich(adopted, adoptedVariant);
        if (changed) publish();
    }

    /** Attaches a freshly captured frame to whichever detected video it came from. */
    public void noteFrame(@Nullable String hint, String src, @Nullable String poster,
                          long durationMs, String dataUri) {
        if (TextUtils.isEmpty(dataUri)) return;
        synchronized (lock) {
            MediaItem match = match(hint, src, poster, durationMs);
            if (match == null) return;
            match.liveFrame = dataUri;
            // A frame can only have come from the element that is playing, so this doubles as
            // confirmation of which video that is — more reliable than the hint alone, and it
            // is what puts the video being watched at the front of the sheet.
            // Cleared unconditionally, not only when this item is not already the playing one.
            // One video is on screen at a time, so at most one may carry the mark, and a guard
            // that skips the sweep whenever the answer is already right leaves any stale mark
            // elsewhere in place — two cards then both claim to be playing.
            for (MediaItem other : items.values()) other.playing = other == match;
            match.seenAt = System.currentTimeMillis();
            match.watched = true;
        }
        publish();
    }

    /**
     * Ties a playing {@code <video>} element back to a detected item.
     *
     * <p>On the platforms that matter the element's src is a blob, so the post id the scanner
     * derived from the surrounding permalink is the reliable signal — it is the same id the
     * extractors attach to the media they pull out of the page's JSON. Everything after that
     * is a fallback.
     */
    @Nullable
    private MediaItem match(@Nullable String hint, String src, @Nullable String poster,
                            long durationMs) {
        MediaItem found = resolveMatch(hint, src, poster, durationMs);
        // Once per outcome, not once per frame: the player reports continuously and an
        // unfiltered line here would bury everything else in the log.
        String line = "match hint=" + (hint == null ? "-" : hint) + " dur=" + durationMs
                + " of " + items.size() + " -> "
                + (found == null ? "NONE" : found.groupKey);
        if (!line.equals(lastMatchLog)) {
            lastMatchLog = line;
            Log.i(DIAG, line);
        }
        return found;
    }

    @Nullable
    private MediaItem resolveMatch(@Nullable String hint, String src, @Nullable String poster,
                                   long durationMs) {
        // The file the element is actually playing, when it has a real one. Checked before the
        // post id because it is the one signal that cannot be out of date: it is the video
        // itself, not a link found near it. Instagram's address bar and its permalinks both
        // settle a moment after the next reel starts, and trusting them first is what showed
        // the reel just watched as the one now playing.
        if (!TextUtils.isEmpty(src) && !src.startsWith("blob:")) {
            MediaItem byUrl = owners.get(src);
            if (byUrl != null) return byUrl;
        }

        if (!TextUtils.isEmpty(hint)) {
            MediaItem byHint = hints.get(hint);
            // Believed only when the running time agrees. Two independent facts that disagree
            // — the id says one video, the length says another — is what a permalink that has
            // not caught up looks like, so the id is not taken on its own.
            if (byHint != null && !contradicts(byHint, durationMs)) return byHint;

            // The id led nowhere. Whether that ends the search is the platform's call: where
            // ids are dependable, one we do not hold means a video we have not indexed rather
            // than one of the others, and guessing on from here is how a reel's frame and
            // caption landed on its neighbour. Where they are not, giving up means never
            // recognising the video playing at all.
            if (SitePolicies.forHost(Formats.hostOf(pageUrl)).strictHintMatch()) return null;
        }
        if (!TextUtils.isEmpty(poster)) {
            // Compared as the platform says two posters are the same picture, not as strings.
            // A CDN that re-signs its images returns a different address for the same file every
            // time it is asked, so string equality answers "was this the same fetch" when the
            // question is "is this the same video". See SitePolicy.posterIdentity.
            SitePolicy policy = SitePolicies.forHost(Formats.hostOf(pageUrl));
            String wanted = policy.posterIdentity(poster);
            if (!TextUtils.isEmpty(wanted)) {
                for (MediaItem item : items.values()) {
                    if (TextUtils.isEmpty(item.posterUrl)) continue;
                    if (wanted.equals(policy.posterIdentity(item.posterUrl))) return item;
                }
            }
        }
        if (durationMs > 0) {
            // The closest running time within tolerance, and how much better it is than the
            // runner-up. Where a video plays through MediaSource its address is a blob and its
            // page may name no id, which leaves length as the only fact tying the element on
            // screen to anything indexed — so it has to be used as well as it can be.
            MediaItem best = null;
            long bestGap = Long.MAX_VALUE;
            long runnerUpGap = Long.MAX_VALUE;
            for (MediaItem item : items.values()) {
                if (item.durationMs <= 0) continue;
                long gap = Math.abs(item.durationMs - durationMs);
                if (gap > DURATION_TOLERANCE_MS) continue;
                if (gap < bestGap) {
                    runnerUpGap = bestGap;
                    bestGap = gap;
                    best = item;
                } else if (gap < runnerUpGap) {
                    runnerUpGap = gap;
                }
            }
            // One candidate is decisive. Several are only decisive when one of them is clearly
            // closer than the rest: two clips of genuinely equal length cannot be told apart
            // this way, and picking either is how one video's frame lands on another's card.
            if (best != null
                    && (runnerUpGap == Long.MAX_VALUE || runnerUpGap - bestGap > CLEAR_WINNER_MS)) {
                return best;
            }
        }
        // A single candidate on the page needs no disambiguation.
        if (items.size() == 1) return items.values().iterator().next();
        return null;
    }

    /**
     * Whether the running time on screen rules this item out.
     *
     * <p>Only when both are known. A video still loading reports nothing, and an item whose
     * duration has not been read yet knows nothing — neither is evidence of a mismatch.
     */
    private static boolean contradicts(MediaItem item, long durationMs) {
        return durationMs > 0 && item.durationMs > 0
                && Math.abs(item.durationMs - durationMs) > DURATION_TOLERANCE_MS;
    }

    // ---------------------------------------------------------------- internals

    /**
     * Caller must hold {@code lock}.
     *
     * <p>Wrapper exists only to say, in the log, which card a URL landed on and why. Two
     * renditions of one video quietly becoming two cards is invisible in every other line the
     * detector prints, and the probe log cannot show it: both renditions are probed before
     * either finishes decoding, so both are marked strict whether they were grouped or not.
     */
    @Nullable
    private MediaItem itemFor(String url, @Nullable String hint) {
        int before = items.size();
        MediaItem item = resolveItem(url, hint);
        Log.i(DIAG, "group " + shorten(url)
                + " hint=" + (hint == null ? "-" : hint)
                + " -> " + (item == null ? "DROPPED"
                        : item.groupKey + (items.size() > before ? " NEW" : " joined")));
        return item;
    }

    @Nullable
    private MediaItem resolveItem(String url, @Nullable String hint) {
        // A video can be known by two names, and both have to lead to the same card.
        //
        // The payload states one — the post id its JSON carries — and the address carries the
        // other, written into the file name by the CDN. A rendition listed in the JSON arrives
        // under the first; the same video overheard on the wire arrives under the second. Look
        // up only one of them and the two are strangers: the payload builds a card with the
        // full ladder on it, and every rendition actually fetched builds a card of its own
        // beside it. That is one video appearing three times.
        // More than one, where the platform can name the same file two ways and does not always
        // send both — see groupKeysFor. Every one of them ends up pointing at the card, so a
        // request carrying only the name we happen not to have used finds it rather than
        // starting a second copy beside it.
        List<String> addressKeys =
                SitePolicies.forHost(Formats.hostOf(pageUrl)).groupKeysFor(url);
        String addressKey = addressKeys.isEmpty() ? null : addressKeys.get(0);
        if (hint == null) hint = addressKey;

        MediaItem item = named(hint);
        for (int i = 0; item == null && i < addressKeys.size(); i++) {
            item = named(addressKeys.get(i));
        }
        if (item == null) item = owners.get(url);
        if (item != null) {
            // Learned further names for a card already held.
            remember(hint, item);
            for (String key : addressKeys) remember(key, item);
            return item;
        }

        String key = hint != null ? "hint:" + hint : UrlClassifier.groupKey(url);
        item = items.get(key);
        if (item == null) {
            // Make room rather than refusing. Returning null here meant that once a feed had
            // produced enough videos, nothing new was ever detected again — the detector simply
            // went dead partway down a long scroll.
            while (items.size() >= MAX_ITEMS && evictOldest()) {
                // keep going until there is space
            }
            if (items.size() >= MAX_ITEMS) return null;

            item = new MediaItem(key);
            item.pageUrl = pageUrl;
            item.preferProgressive =
                    SitePolicies.forHost(Formats.hostOf(pageUrl)).preferProgressive();
            // Only if the title on hand is this video's to take. Otherwise it is left empty
            // and filled in by setPageTitle when the right one arrives.
            item.pageTitle = titleBelongsTo(pageUrl) ? pageTitle : "";
            items.put(key, item);
        }
        remember(hint, item);
        for (String name : addressKeys) remember(name, item);
        return item;
    }

    /**
     * The most recent video the site described and that is ready to offer, or null if it
     * described none. Caller must hold {@code lock}.
     *
     * <p>Only a presentable one counts. A record that has just arrived and cannot yet be offered
     * must not displace the one already on the sheet, or the row would blink out each time the
     * player moved on and come back a moment later.
     */
    @Nullable
    private MediaItem latestDeclared() {
        MediaItem latest = null;
        for (MediaItem item : items.values()) {
            if (!item.declared || !item.presentable()) continue;
            if (latest == null || item.discoveredAt > latest.discoveredAt) latest = item;
        }
        return latest;
    }

    /** A card already known by this name, or null. Caller must hold {@code lock}. */
    @Nullable
    private MediaItem named(@Nullable String name) {
        return name == null ? null : hints.get(name);
    }

    /** Files a card under one of its names. Caller must hold {@code lock}. */
    private void remember(@Nullable String name, MediaItem item) {
        if (name != null) hints.put(name, item);
    }

    /**
     * Drops the video found longest ago, never the one on screen.
     *
     * <p>Caller must hold {@code lock}.
     *
     * @return whether anything was removed
     */
    private boolean evictOldest() {
        MediaItem oldest = null;
        for (MediaItem candidate : items.values()) {
            if (candidate.playing) continue;
            if (oldest == null || candidate.discoveredAt < oldest.discoveredAt) oldest = candidate;
        }
        if (oldest == null) return false;

        items.remove(oldest.groupKey);
        owners.values().removeAll(Collections.singleton(oldest));
        hints.values().removeAll(Collections.singleton(oldest));
        return true;
    }

    private static void mergeHeaders(MediaVariant v, @Nullable Map<String, String> headers) {
        if (headers == null) return;
        for (Map.Entry<String, String> e : headers.entrySet()) {
            // Network-captured headers are more trustworthy; do not overwrite them.
            if (!v.headers.containsKey(e.getKey())) v.headers.put(e.getKey(), e.getValue());
        }
    }

    private static void setPoster(MediaItem item, String poster,
                                  @Nullable Map<String, String> headers) {
        item.posterUrl = poster;
        item.posterHeaders.clear();
        if (headers != null) item.posterHeaders.putAll(headers);
    }

    /**
     * Step one of the pipeline: work out what the URL really is and whether we can fetch it.
     * A playlist is expanded into its qualities; anything else gets a HEAD/Range probe.
     */
    private void enrich(MediaItem item, MediaVariant variant) {
        if (variant == null || variant.probed) return;

        boolean manifest = variant.kind == MediaKind.HLS || variant.kind == MediaKind.DASH;

        synchronized (lock) {
            if (!inFlight.add(variant.url)) return;
            // Manifests are always worth resolving: they are the only source of the quality
            // list. Plain size probes are the ones that can run away on a busy page.
            if (!manifest && ++probesUsed > PROBE_BUDGET) {
                inFlight.remove(variant.url);
                variant.probed = true;
                return;
            }
        }

        if (variant.kind == MediaKind.DASH) {
            probePool.execute(() -> dashResolver.resolve(item, variant, resolved -> {
                synchronized (lock) {
                    inFlight.remove(variant.url);
                    for (MediaVariant v : resolved.variants()) owners.put(v.url, resolved);
                }
                scheduleMetadata(resolved);
                publish();
            }));
            return;
        }

        if (variant.kind == MediaKind.HLS) {
            probePool.execute(() -> hlsResolver.resolve(item, variant, resolved -> {
                synchronized (lock) {
                    inFlight.remove(variant.url);
                    // Renditions the master expanded into belong to this item, not a new one.
                    for (MediaVariant v : resolved.variants()) owners.put(v.url, resolved);
                }
                scheduleMetadata(resolved);
                publish();
            }));
            return;
        }

        probePool.execute(() -> {
            Prober.Result r = prober.probe(variant.url, variant.headers);
            boolean nowPlaylist = false;
            synchronized (lock) {
                variant.probed = true;
                inFlight.remove(variant.url);
                if (r != null) {
                    // The server's answer wins outright, including when it says "not video".
                    variant.kind = r.kind;
                    if (r.mime != null) variant.mime = r.mime;
                    if (r.contentLength > 0) {
                        variant.sizeBytes = r.contentLength;
                    }
                    variant.acceptsRanges = r.acceptsRanges;
                    if (r.workingHeaders != null) {
                        // The captured headers were refused and a relaxed set got through.
                        // Keep the set that works so the download does not rediscover it.
                        variant.headers.clear();
                        variant.headers.putAll(r.workingHeaders);
                    }
                    nowPlaylist = variant.kind == MediaKind.HLS;
                } else if (variant.kind == MediaKind.UNKNOWN) {
                    // Unreachable out of band, so a download would fail too.
                    variant.kind = MediaKind.NONE;
                }
                if (nowPlaylist) variant.probed = false;
            }

            if (nowPlaylist) {
                enrich(item, variant);
                return;
            }
            Log.i(DIAG, "probe " + shorten(variant.url) + " -> "
                    + (r == null ? "UNREACHABLE"
                    : r.kind + " " + r.mime + " " + r.contentLength + "B"));

            if (!variant.kind.downloadable()) {
                publish();
                return;
            }
            // A failed probe is not a verdict: HEAD and a zero-length range are both refused by
            // some CDNs that serve an ordinary ranged GET perfectly well, and giving up here
            // meant those URLs were never opened at all. Opening it is the real test.
            //
            // Once one variant of an item has been decoded, its siblings are alternative
            // encodes of the same thing and a successful probe is enough for them — which is
            // what keeps the other qualities without paying for a decode each.
            boolean strict = !item.videoConfirmed;
            if (!strict && r != null && variant.height > 0) {
                synchronized (lock) {
                    variant.verified = true;
                    variant.inspected = true;
                }
                publish();
                return;
            }
            inspect(item, variant, strict);
        });
    }

    /**
     * Step two: open the video and read what only the file knows — its real resolution, its
     * duration, and a frame to show. This is what turns a nameless "Original" into "1080p"
     * and gives every card a preview instead of only the one being watched.
     */
    private void scheduleMetadata(MediaItem item) {
        MediaVariant target = thumbnailTarget(item);
        if (target == null) {
            // Nothing left worth opening: either a frame is already in hand, or every
            // variant has been tried. Without one the item stays out of the sheet.
            publish();
            return;
        }
        inspect(item, target, false);
    }

    /**
     * Opens the video and reads what only the file knows — its real resolution, its duration,
     * and a frame to show.
     *
     * <p>This is also the gate that keeps junk out. Instagram and Facebook feed MediaSource by
     * fetching byte-range slices of a stream; those respond 200 to a probe and look like small
     * MP4s, but they have no dimensions and no duration because they are fragments, not files.
     * Requiring a decodable video track is what distinguishes a video the user can watch from
     * a piece of one.
     *
     * @param requireVideo drop the variant when it does not decode as a video. False for HLS,
     *                     whose renditions the master playlist has already vouched for.
     */
    private void inspect(MediaItem item, MediaVariant target, boolean requireVideo) {
        synchronized (lock) {
            if (!metadataQueued.add(target.url) || ++metadataUsed > METADATA_BUDGET) {
                // Unverified and unverifiable within budget, so it stays out of the sheet.
                publish();
                return;
            }
        }

        // Always decode a frame unless we already hold one. A platform poster is a URL that can
        // 403, expire, or simply never load, and skipping the capture because one exists is why
        // some cards showed no preview at all. The stream is open anyway, so the frame is close
        // to free, and a locally decoded frame always beats a remote poster.
        final boolean needsFrame = TextUtils.isEmpty(item.localThumbnail);
        // Only the variant that has to prove itself is worth paying a download for.
        final boolean allowDownloadFallback;
        synchronized (lock) {
            allowDownloadFallback = requireVideo && ++downloadFallbacksUsed <= DOWNLOAD_FALLBACKS;
        }

        metadataPool.execute(() -> {
            MetadataReader.Result result =
                    readWithTimeout(target, needsFrame, allowDownloadFallback);

            synchronized (lock) {
                target.inspected = true;
                boolean playable = result != null && result.playableVideo();
                boolean accepted;

                if (playable) {
                    if (result.width > 0) target.width = result.width;
                    if (result.height > 0) target.height = result.height;
                    if (result.bitrate > 0 && target.bandwidth <= 0) target.bandwidth = result.bitrate;
                    // A partial read timed only the few seconds we fetched, so it must never
                    // stand in for the real running time.
                    if (result.durationMs > 0 && !result.partialDuration && item.durationMs <= 0) {
                        item.durationMs = result.durationMs;
                    }
                    if (result.thumbnailPath != null) item.localThumbnail = result.thumbnailPath;
                    target.verified = target.kind.downloadable();
                    target.decoded = true;
                    // Vouches for every other rendition of this video.
                    item.videoConfirmed = true;
                    accepted = true;

                } else if (!requireVideo) {
                    // A sibling of an already-decoded video: same ladder, same source.
                    target.verified = target.kind.downloadable();
                    accepted = true;

                } else {
                    // A fragment, an image, an audio track, or something unplayable.
                    target.kind = MediaKind.NONE;
                    target.verified = false;
                    accepted = false;
                }

                Log.i(DIAG, "inspect " + target.kind + ' ' + shorten(target.url)
                        + " -> " + (playable
                        ? result.width + "x" + result.height + ' ' + result.durationMs + "ms"
                        + (result.thumbnailPath == null ? " NO-FRAME" : " +frame")
                        : "UNDECODABLE")
                        + (accepted ? " accepted" : " REJECTED")
                        + (requireVideo ? " [strict]" : " [sibling]"));

                item.sortVariants();
            }
            publish();
        });
    }

    /**
     * {@link android.media.MediaMetadataRetriever} has no timeout of its own, and a URL whose
     * moov atom sits at the end of the file will read forever. The reader runs on its own
     * expendable thread so a stuck one cannot block the queue behind it.
     */
    @Nullable
    private MetadataReader.Result readWithTimeout(MediaVariant target, boolean needsFrame,
                                                  boolean allowDownloadFallback) {
        Future<MetadataReader.Result> future = readerPool.submit(
                () -> metadataReader.read(target.url, target.headers, needsFrame,
                        allowDownloadFallback));
        try {
            return future.get(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Which variant to open for a poster frame. */
    @Nullable
    private MediaVariant thumbnailTarget(MediaItem item) {
        // Only a frame we decoded ourselves counts as having one; a poster URL does not.
        if (!TextUtils.isEmpty(item.localThumbnail)) return null;

        MediaVariant best = null;
        for (MediaVariant v : item.variants()) {
            if (!v.kind.downloadable() || v.inspected) continue;
            if (best == null || betterToDecode(v, best)) best = v;
        }
        return best;
    }

    /**
     * A self-contained file opens far more reliably than a playlist, so a progressive variant
     * always wins. Among equals the smallest rendition is cheapest to fetch a frame from.
     *
     * <p>This used to pick purely by {@link MediaVariant#rank()}, which reports 0 for an
     * unknown height — making an unlabeled playlist look like the cheapest option and sending
     * every decode at a manifest instead of the MP4 sitting beside it.
     */
    private static boolean betterToDecode(MediaVariant candidate, MediaVariant current) {
        boolean candidateIsFile = candidate.kind == MediaKind.PROGRESSIVE;
        boolean currentIsFile = current.kind == MediaKind.PROGRESSIVE;
        if (candidateIsFile != currentIsFile) return candidateIsFile;
        return effectiveRank(candidate) < effectiveRank(current);
    }

    /** Unknown quality sorts last rather than first. */
    private static int effectiveRank(MediaVariant variant) {
        int rank = variant.rank();
        return rank <= 0 ? Integer.MAX_VALUE : rank;
    }

    /** Candidates found on this page that have not passed verification. */
    public LiveData<Integer> unresolved() {
        return unresolvedLive;
    }

    /** Only fully resolved items reach the UI. */
    private void publish() {
        List<MediaItem> visible = new ArrayList<>();
        int unresolved = 0;
        SheetRules rules;
        synchronized (lock) {
            SitePolicy policy = SitePolicies.forHost(Formats.hostOf(pageUrl));
            // What the sheet holds and how it is ordered, as the platform's own object. Both
            // decisions used to be made here from a handful of knobs, which meant tuning one
            // platform's list rewrote the code every other platform's list was built from.
            rules = policy.sheetRules();
            // When the address names one post, that post is the answer — even though the feed it
            // was opened from is still loaded behind it and still full of videos. Falls back to
            // showing everything when the named post is not held, so this can narrow the sheet
            // but never empty it.
            MediaItem solo = named(policy.soloPostFor(pageUrl));

            // Records the site described that have since been completed — a poster or a running
            // time that arrived after the address did. Asked here because this is the one place
            // every path ends up, so a late-arriving part cannot leave a good video sitting in
            // the unresolved tally with nothing left to trigger a second look.
            if (policy.trustDeclaredMedia()) {
                for (MediaItem item : items.values()) item.trustDeclared();
            }

            // Nothing is offered as a stand-in for the video playing.
            //
            // Guessing was tried, in both directions, and both were wrong. Offering the newest
            // withheld candidate named the clip queued next rather than the one on screen.
            // Offering the earliest fixed that but kept the deeper fault: on a page with no
            // video playing at all — a wiki article carrying an advert, or a "you might like"
            // strip — there is still something withheld, so the sheet claimed a video where the
            // viewer could see there was none.
            //
            // A playing-only sheet answers one question, and a guess is not an answer to it. If
            // the video on screen was not recognised, the honest result is an empty sheet and a
            // matching problem to fix, not a plausible-looking wrong row.
            // On a player that shows one clip at a time, the newest record the site gave us is
            // the clip on screen. Older records are what has been watched; anything with no
            // record at all was inferred from traffic, which is mostly the clip being fetched
            // ahead. Null unless the platform asks for this, and null when nothing was described
            // — in which case the list is left exactly as it would have been.
            MediaItem latest = policy.latestDeclaredOnly() ? latestDeclared() : null;

            for (MediaItem item : items.values()) {
                if (solo != null && item != solo) continue;
                if (latest != null && item != latest) continue;
                // Left out because of what it is, not because anything failed to read it — so
                // it is skipped before the unresolved tally, like the two rules above it.
                if (!rules.admits(item, items.values())) continue;
                if (item.presentable()) visible.add(item);
                else unresolved++;
            }
        }
        unresolvedLive.postValue(unresolved);

        rules.sort(visible);
        live.postValue(visible);
    }

    public int count() {
        List<MediaItem> v = live.getValue();
        return v == null ? 0 : v.size();
    }

    /** Long CDN URLs make the log unreadable; the tail identifies them well enough. */
    private static String shorten(String url) {
        if (url == null) return "null";
        String host = UrlClassifier.groupKey(url);
        return host.length() <= 90 ? host : host.substring(0, 90) + "…";
    }
}
