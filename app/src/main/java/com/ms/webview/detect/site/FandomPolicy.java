package com.ms.webview.detect.site;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fandom / Wikia.
 *
 * <p>Its own file, so what is set here reaches Fandom and nothing else.
 */
public class FandomPolicy implements SitePolicy {

    /**
     * A video hub address names its clip outright: {@code /video/YwDct0YS/ad-21}. The token is
     * JW Player's media id, the same one the catalogue is keyed on.
     *
     * <p>Worth far more than it looks. Everywhere else on Fandom the clip has to be guessed at
     * from the page's markup, and a hub page's markup mentions a dozen ids — the rail of things
     * to watch next — with no way to tell which one the player opened with. Here the address
     * settles it, so the video can be fetched directly and anything that is not it refused.
     */
    private static final Pattern HUB_MEDIA_ID =
            Pattern.compile("/video/([A-Za-z0-9]{6,12})(?:[/?#]|$)");

    /**
     * The clip this address names, or null when it names none.
     *
     * <p>Lives here for the same reason the recommendation list does: Fandom's idea of what its
     * own addresses mean is one thing, in Fandom's own file, and changing it cannot reach another
     * site.
     */
    @Nullable
    public static String mediaIdIn(@Nullable String pageUrl) {
        if (pageUrl == null || pageUrl.isEmpty()) return null;
        Matcher m = HUB_MEDIA_ID.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Addresses that serve Fandom's global content rather than an article's own video —
     * recommendation rails, partner slots, sponsored and trending feeds.
     *
     * <p>These are requested on every wiki page whether or not the article has a video of its
     * own, which is how a page of plain text came to offer a download.
     */
    private static final String[] RECOMMENDATION_URLS = {
            "/recommendations",
            "/recommended",
            "featured-video",
            "/suggested",
            "/partner-slot",
            "/partner_slot",
            "jwplatform.com/feed",
            "cdn.jwplayer.com/v2/media/recommendations",
            "/trending",
            "/sponsored",
            "wikia-services.com/recommendations",
    };

    /**
     * Whether a payload came from one of those addresses.
     *
     * <p>Lives here rather than in the extractor so the list of what counts as Fandom's own
     * content is one thing, in Fandom's own file, and changing it cannot reach another site.
     */
    public static boolean isRecommendationUrl(@Nullable String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase(java.util.Locale.US);
        for (String pattern : RECOMMENDATION_URLS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    @Override
    public boolean appliesTo(String host) {
        return host.contains("fandom.com") || host.contains("wikia.com")
                || host.contains("wikia.nocookie.net") || host.contains("wikia-services.com")
                || host.contains("nocookie.net") || host.contains("wikia.org");
    }

    /**
     * JW Player writes the clip's id into the address of everything it serves for that clip —
     * the manifest, each rendition, the poster.
     */
    private static final Pattern MEDIA_URL_ID = Pattern.compile(
            "(?:jwplayer|jwplatform)\\.com/(?:v2/)?(?:media|manifests|videos)/([A-Za-z0-9]{6,12})");

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * The clip a media address belongs to, named the way {@code FandomExtractor} names it.
     *
     * <p>Without this the renditions overheard on the wire have nothing textually in common with
     * the record fetched from the catalogue, so one video arrives as several cards — the record
     * as one, each overheard rendition as another. Since the id is in both, they can be put back
     * together, and the qualities land on the card that has the title and the poster instead of
     * beside it.
     */
    @Nullable
    @Override
    public String groupKeyFor(String url) {
        if (url == null) return null;
        Matcher m = MEDIA_URL_ID.matcher(url);
        return m.find() ? "fandom:" + m.group(1) : null;
    }

    /**
     * Per URL. An article's title describes the article rather than any clip embedded in it, and
     * it changes a beat after the address does — pairing the two stops a title following the
     * viewer onto the next page.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * False, and this one is settled.
     *
     * <p>It was set true four times, on the reasoning that a wiki page should offer the clip
     * being watched rather than the queue behind it. Every attempt hid the video instead, and the
     * cause was the same each time: showing only the playing clip requires knowing which clip is
     * playing, and on Fandom nothing can establish that. The address is a wiki article carrying
     * no media id, so the scanner has no name to offer. The player runs through MediaSource, so
     * the element's own address is a blob and cannot be taken directly. What is left is a running
     * time, which is not known until the file has been opened — and until then the video is
     * hidden, on a page where it is plainly playing.
     *
     * <p>The rule also fails silently, which is what made it so costly: an item skipped this way
     * is not counted as unresolved either, so the sheet reports finding nothing rather than
     * finding something it will not show.
     *
     * <p>Keeping the queue out is a detection question and belongs to
     * {@link com.ms.webview.detect.extract.FandomExtractor}, which reads only the clip at the
     * front of the player's payload and drops the rest. That decides what exists, instead of
     * hiding what does — and it works whether or not anything is recognised as playing.
     */
    @Override
    public boolean playingOnly() {
        return false;
    }

    /**
     * True. Where a Fandom clip is served as a plain file rather than through MediaSource,
     * taking the playing element's own address detects it without waiting to overhear the
     * request.
     */
    @Override
    public boolean adoptPlayingSource() {
        return true;
    }

    /**
     * True. Fandom's clips come from JW Player's catalogue, and what comes back is the same
     * record the player itself is given: the file's address, its running time, its poster. There
     * is nothing a local decode would add to that except delay — and when the decode failed, a
     * video that plays perfectly well was offered as one that "could not be opened for
     * download", which is simply untrue.
     */
    @Override
    public boolean trustDeclaredMedia() {
        return true;
    }

    /**
     * True. Fandom's player carries one clip at a time and fetches a fresh record whenever it
     * moves on, so the newest record is what is on screen — which is what keeps a clip already
     * watched from staying in the list, and keeps the clip being fetched ahead out of it, without
     * needing to recognise which element is playing.
     */
    @Override
    public boolean latestDeclaredOnly() {
        return true;
    }

    /**
     * True. Fandom's player swaps clips behind an address change without loading a page, so the
     * detector is left holding whatever the previous clip left behind. Reloading gives it a clean
     * page and the new clip's own record.
     *
     * <p>Safe here for the reason it would not be on a feed: Fandom changes the address when the
     * viewer moves to another video, not while they scroll past one, so this fires on the move
     * rather than on the movement.
     */
    @Override
    public boolean reloadOnRouteChange() {
        return true;
    }

    /**
     * False. Nothing on a Fandom page produces an id the extractor would recognise, so there is
     * no id to be strict about — and being strict about an absent one would only close off the
     * weaker signals that do work here.
     */
    @Override
    public boolean strictHintMatch() {
        return false;
    }

    /**
     * True. Moving to another article, or reloading the one in view, replaces what is on screen
     * entirely — the previous page's queue is not what the viewer is looking at any more.
     *
     * <p>Only a change of path counts, so the parameters Fandom appends to a page standing still
     * do not clear the sheet.
     */
    @Override
    public boolean resetOnRouteChange() {
        return true;
    }
}
