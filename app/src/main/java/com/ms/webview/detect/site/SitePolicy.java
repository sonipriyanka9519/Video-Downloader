package com.ms.webview.detect.site;

import java.util.Collections;
import java.util.List;

/**
 * Per-platform behaviour, kept out of the shared engine.
 *
 * <p>The detector, the registry and the sheet are one machine used by every site, so a change
 * made for one platform used to land on all of them — tuning Facebook's ordering changed
 * Instagram's, which is not a trade anyone agreed to. Anything that should differ between
 * platforms belongs here instead, in that platform's own file, where changing it cannot reach
 * anywhere else.
 */
public interface SitePolicy {

    /** Lower-case host of the page. */
    boolean appliesTo(String host);

    /**
     * What the sheet holds and how it is ordered.
     *
     * <p>The default is {@link DefaultSheetRules}, which is what every platform did before any of
     * them had rules of its own — so returning nothing here changes nothing. Returning a class of
     * your own moves both decisions into that class, where they can be changed without any other
     * site noticing. See {@link SheetRules}.
     */
    default SheetRules sheetRules() {
        return new DefaultSheetRules(this);
    }

    /**
     * How the sheet arranges videos that are neither playing nor already watched.
     *
     * <p>{@link Order#NEWEST} — most recently found first. On a platform that prefetches while
     * you scroll, the clips just turned up are the ones coming next and the ones found long
     * ago are already behind you.
     *
     * <p>{@link Order#FEED} — oldest found first, for a page that lists everything up front in
     * the order it will be watched.
     */
    Order order();

    /** What the document title may be used for. See {@link PageTitleUse}. */
    PageTitleUse pageTitleUse();

    /**
     * True to admit only videos that have actually been on screen.
     *
     * <p>A feed hands over the next several clips in one response long before the viewer
     * reaches them. On a platform where that is the norm, listing all of them turns a sheet
     * for the video being watched into a list of things not yet seen — so a video is admitted
     * when it plays, and each further one joins as the viewer scrolls onto it.
     */
    boolean playingOnly();

    /**
     * What to do when the post id taken from the page names a video the registry has never
     * indexed.
     *
     * <p>True: give up. On a platform whose ids are dependable, an id we do not hold means the
     * video on screen is one we have not seen yet — not one of the others — and guessing from
     * a shared duration is how a reel's frame and caption land on its neighbour.
     *
     * <p>False: carry on to the weaker signals, poster and running time. Worth it where the
     * ids do not always line up, because giving up there means the video playing is never
     * recognised at all — no preview, and nothing ever marked as watched.
     */
    boolean strictHintMatch();

    /**
     * What makes two poster addresses the same picture.
     *
     * <p>The address itself by default, so two posters match when they are the same string. That
     * is right nearly everywhere and it is the safest possible test.
     *
     * <p>It is wrong on a platform that re-signs its images. The video on screen is tied back to
     * a detected one chiefly by its poster — the element's src is a {@code blob:} on any
     * MediaSource player, so the picture is the strongest thing left — but the poster the page
     * scanner reads off the element and the poster the extractor took from the payload are two
     * separate fetches of one image, and a CDN that stamps a fresh signature and expiry into the
     * query hands back two different strings for it. They never compare equal, the strongest
     * signal never fires, and matching falls through to guessing by running time: on a feed of
     * clips that are all about the same length, that is how the video on screen ends up
     * recognised as a neighbour, or as nothing at all.
     *
     * <p>Returning the stable part of the address — the path, typically — makes the comparison
     * about the picture rather than about the fetch.
     */
    default String posterIdentity(String posterUrl) {
        return posterUrl;
    }

    /**
     * A grouping key read out of the media address itself, or null to key on the address.
     *
     * <p>For the case where a quality ladder is discovered by overhearing traffic rather than
     * by reading the page's JSON: there is no post node to take an id from, and the addresses
     * of two renditions of one video have nothing textually in common, so each becomes its own
     * card. Where a platform writes the video's id into the address, this recovers it.
     *
     * <p>Default is null — no platform gets this behaviour unless its own file asks for it.
     */
    default String groupKeyFor(String url) {
        return null;
    }

    /**
     * Every name this address is known by, so a card can be found under any of them.
     *
     * <p>{@link #groupKeyFor(String)} gives one name, and one is not always enough. A platform
     * can identify the same file two ways at once — an id inside a query parameter and the path
     * it sits on — and which of them is available varies from request to request. A card built
     * under the first is then invisible to a later request carrying only the second, and turns up
     * as a second card for a video already listed. Returning both closes that: whichever name
     * arrives, it leads to the same card, and the card learns the other one.
     *
     * <p>Defaults to whatever {@link #groupKeyFor(String)} gives, so a site that does not
     * override this behaves exactly as before.
     */
    default List<String> groupKeysFor(String url) {
        String key = groupKeyFor(url);
        return key == null ? Collections.emptyList() : Collections.singletonList(key);
    }

    /**
     * The whole file a byte-range slice was cut from, or null to keep discarding slices.
     *
     * <p>A slice is never offerable on its own — a few hundred kilobytes with no moov atom — so
     * the engine drops them, and on most sites that is right: the whole file is requested
     * plainly somewhere too, and that request is the one worth keeping.
     *
     * <p>On a platform that plays entirely through MediaSource there is no such request. Every
     * fetch is a slice and the element's own src is a blob, so discarding slices means
     * discarding everything and detecting nothing at all. Where the range travels in the query
     * string, the same address without it is the complete file, and this recovers it.
     *
     * <p>Default is null — no platform gets this behaviour unless its own file asks for it.
     */
    default String wholeFileFor(String url) {
        return null;
    }

    /**
     * What this address is a view of, in the platform's own terms — or null to judge it by its
     * path, as every site is judged unless its own file says otherwise.
     *
     * <p>The path is the right default and the reason is worth keeping: a site routinely rewrites
     * its address after load, dropping a redirect marker or appending a tracking tag, with the
     * page unchanged beneath it. Treating that as a new view wiped a feed the instant it was
     * detected, over and over.
     *
     * <p>But the path only stands in for the content. Where a platform opens a video as a
     * parameter on a page whose path never moves, the path says "same view" while the viewer is
     * looking at something else entirely — so the previous video's findings stay in the sheet
     * beside the new one. Naming the view directly fixes that without loosening anything: a
     * tracking tag still does not change the name, because the name is the video.
     *
     * <p>Default is null — no platform gets this behaviour unless its own file asks for it.
     */
    default String routeKeyFor(String url) {
        return null;
    }

    /**
     * Whether moving to a new address on the same site starts the search over.
     *
     * <p>False by default, and that default matters: a reels feed rewrites its path to the
     * current clip on every scroll, so clearing on a path change emptied the sheet each time
     * the viewer moved on — and because those platforms hand over several clips in one
     * response, the discarded ones were never offered again.
     *
     * <p>True where a route change is a deliberate act rather than a side effect of scrolling.
     * Opening something from a grid means the viewer has chosen one thing out of many, and
     * carrying the whole grid along behind it is the wrong answer to "what is on screen".
     */
    default boolean resetOnRouteChange() {
        return false;
    }

    /**
     * Whether a video stays listed after it stops playing. Only consulted where
     * {@link #playingOnly()} is on, since it is the rule about what that admits.
     *
     * <p>True by default, and normally right: having watched something is a reason to offer it,
     * not a reason to withdraw it. Scrolling past a clip should not take away the chance to
     * save it.
     *
     * <p>False turns the sheet into a view of the current video alone. For a feed where the
     * point is what is on screen right now and a growing list of everything scrolled past is
     * just clutter. The cost is real and worth stating: scroll on, and the previous video is no
     * longer offered.
     */
    default boolean keepWatched() {
        return true;
    }

    /**
     * Whether the source of the element currently playing is taken as a detected video in its
     * own right.
     *
     * <p>False by default. Normally a video is detected by overhearing the page fetch it or by
     * reading it out of the page's JSON, and the playing element is then matched back to what
     * was already found. That keeps a card tied to the post's real title and poster.
     *
     * <p>True where that matching cannot be relied on. On some feeds the playing element's
     * address does not line up with anything the passive layers recorded — a redirect, a
     * per-play URL — so the video plainly on screen is never recognised, and a sheet that shows
     * only the current video is left empty. Adopting the element's own source guarantees the
     * video being watched is always detectable, which is how a plain downloader treats any
     * &lt;video&gt; on the page: whatever is playing, that is the thing to grab.
     */
    default boolean adoptPlayingSource() {
        return false;
    }

    /**
     * The one post this address is about, named the way the extractors name it — or null when
     * the address is a feed rather than a single post.
     *
     * <p>For a site that opens a post as a panel over the feed it came from. The address becomes
     * the post's own, but the feed is still loaded underneath with all its videos in the page,
     * so a scan finds every one of them and the sheet answers a question the viewer did not ask.
     * Naming the post lets the sheet show that post alone.
     *
     * <p>Advisory, never a blackout: if nothing is held under this name the sheet falls back to
     * showing everything, because an over-eager filter that empties the sheet is worse than a
     * list that is too long.
     */
    default String soloPostFor(String pageUrl) {
        return null;
    }

    /**
     * Whether a self-contained file, where one exists, is the only thing worth offering.
     *
     * <p>False by default: a quality ladder is normally the better offer, since it reaches
     * resolutions no single file does.
     *
     * <p>True where the ladder cannot be relied on to survive downloading. A progressive file is
     * fetched and kept as-is; an adaptive rendition has to be pulled segment by segment and
     * remuxed, and a platform whose streams come out unplayable is better served by the one file
     * that needs none of that. Only ever drops the ladder when a real file is there to replace
     * it — a stream-only video keeps every rung it has.
     */
    default boolean preferProgressive() {
        return false;
    }

    /**
     * Whether an audio-only stream overheard on this site should be kept and muxed into a video
     * that turns out to have none.
     *
     * <p>False by default, and it has to be. An audio-only response is normally exactly what it
     * looks like — a music player, an autoplaying clip's soundtrack, an advert — and attaching it
     * to an unrelated video would dub the wrong sound onto it. Discarding it is right nearly
     * everywhere.
     *
     * <p>True where the platform plays through MediaSource and serves its picture and its sound as
     * two separate streams. There the audio-only response is not a stray: it is half of the video
     * on screen, and it is the half that was being thrown away. The page fetches both, which is
     * why the video has sound in the browser and none once downloaded — the sound was overheard,
     * classified as not-video, and dropped, and by download time its address was no longer
     * anywhere in the record.
     *
     * <p>Pairing is deliberately narrow: a held track is only ever attached to a variant that has
     * been <em>opened and measured</em> to contain no audio of its own, and never to one that
     * already names a track to mux. So the worst case where this is wrong is a video that was
     * silent anyway.
     */
    default boolean pairsSeparateAudio() {
        return false;
    }

    /**
     * Whether a video the site itself describes may be offered without opening it first.
     *
     * <p>False by default. Normally a candidate is only offered once it has been fetched and
     * decoded, because most of what the detector finds is a guess from the shape of an address
     * and guesses have to be checked.
     *
     * <p>True where the description is not a guess. A platform's own player record — a real file
     * address, its running time, its poster — is the same document the site hands its player, and
     * a decode only confirms what it already says. Waiting for one costs the user seconds on
     * every video and offers nothing at all when it fails, on a file that plays perfectly well.
     * The engine already reasons this way for an HLS master, which is trusted as a video the
     * moment it lists renditions.
     *
     * <p>The probe still runs, for exact dimensions and byte size. It is no longer what decides
     * whether the video can be offered.
     */
    default boolean trustDeclaredMedia() {
        return false;
    }

    /**
     * Whether the sheet should hold only the most recent video the site described.
     *
     * <p>False by default: a page that shows several videos at once means all of them.
     *
     * <p>True for a player that shows one clip at a time and fetches a fresh record each time it
     * moves on. Two things then follow from one rule. The records are the clips actually loaded
     * for viewing, so the newest is the one on screen and the earlier ones are what has already
     * been watched — keeping the newest drops them. And anything the site did not describe was
     * inferred from traffic, which on such a player is largely the clip being fetched ahead —
     * ignoring those keeps the one coming next out of the list.
     *
     * <p>Nothing is hidden unless there is something better to show: with no described video the
     * sheet lists whatever it has, so this can narrow the list but never empty it.
     */
    default boolean latestDeclaredOnly() {
        return false;
    }

    /**
     * Whether the browser should reload the page when the address changes without one.
     *
     * <p>False by default, and it has to be: a feed rewrites its address on every scroll, so
     * reloading on that would throw the viewer back to the top of the page over and over and
     * make the site unusable.
     *
     * <p>True only where a route change means the content genuinely changed and the page will not
     * fetch it again on its own. Reloading is a blunt instrument — it stops whatever is playing —
     * and is a last resort for a player that swaps its content behind an address change the
     * detector cannot otherwise act on.
     */
    default boolean reloadOnRouteChange() {
        return false;
    }

    enum Order {
        FEED,
        NEWEST
    }

    /** Whether a page's own title is any use as the name of a video found on it. */
    enum PageTitleUse {
        /**
         * Only for videos found at the exact address the title arrived for. For a platform
         * that rewrites the address per video: the title lands a beat after the address
         * changes, so without the pairing a video found in between takes the previous one's
         * name.
         */
        PER_URL,
        /** For anything found on the same site. The title describes the page, and the page is
         *  about one thing. */
        PER_PAGE,
        /**
         * Not at all. Some platforms set the document title to the caption of whichever post
         * opened first and then leave it there while you scroll — so using it names every
         * video after the first one. The bare host is a worse name but an honest one.
         */
        NEVER
    }
}
