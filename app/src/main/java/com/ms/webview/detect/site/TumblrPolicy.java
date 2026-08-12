package com.ms.webview.detect.site;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tumblr.
 *
 * <p>Its own file, so what is set here reaches Tumblr and nothing else.
 */
public class TumblrPolicy implements SitePolicy {

    /**
     * A post's own address: tumblr.com/&lt;blog&gt;/&lt;id&gt;/&lt;slug&gt;, or the older
     * blog.tumblr.com/post/&lt;id&gt;. The id is long, which is what keeps the first shape from
     * reading a word like "tagged" or "dashboard" as a post.
     */
    private static final Pattern POST_ADDRESS = Pattern.compile(
            "tumblr\\.com/(?:post/|[^/?#]+/)(\\d{9,})");

    @Override
    public boolean appliesTo(String host) {
        return host.contains("tumblr.com") || host.contains("tumblr.co");
    }

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Per URL. A dashboard's title describes the dashboard, and a permalink's describes the
     * post — pairing the title to the address it arrived for keeps one from being read as the
     * other.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * True — one video at a time, each joining as it plays.
     *
     * <p>This has been turned on and off before, and both earlier failures had the same cause:
     * the sheet could not tell which video was playing, so a playing-only rule hid everything
     * and the page read as empty. That is no longer how it works.
     * {@link #adoptPlayingSource()} takes the playing element's own address as a detected video
     * outright, so a video that plays is always present and always marked — there is no match to
     * fail. A feed's payload may still hand over nine posts at once, but only the one being
     * watched is offered, and the next joins when the viewer reaches it.
     *
     * <p>Watched videos are kept, which is the default: having seen something is a reason to
     * offer it, not to withdraw it. So the list grows one video at a time in the order they were
     * played, rather than arriving whole.
     */
    @Override
    public boolean playingOnly() {
        return true;
    }

    /**
     * True. Tumblr serves its videos as ordinary files, so a paused preview's source is already
     * a real address — which is how a whole tagged wall is detected without waiting for any of
     * it to play. When one is tapped, its source is what marks it the current video.
     */
    @Override
    public boolean adoptPlayingSource() {
        return true;
    }

    /**
     * False. This was true, on the reasoning that showing one video makes a wrong match the
     * whole sheet — but that reasoning only looked at a permalink, and the harder page is the
     * feed: a tagged or dashboard page that lists video after video and never changes its
     * address. There the id the DOM reads from a post's permalink and the id the extractor
     * takes from the same post's JSON do not always spell the same string, and strict threw the
     * match away on that mismatch — leaving a feed with a video plainly playing on it and an
     * empty sheet.
     *
     * <p>False lets a missed id fall through to the running time and the poster, which is what
     * catches the video playing on a feed. A permalink is not put at risk by this: one post is
     * one item, and a single candidate needs no id to be recognised.
     */
    @Override
    public boolean strictHintMatch() {
        return false;
    }

    /**
     * True. Opening a post rewrites the address to its permalink, so a fresh look at what is now
     * on screen is the right response — the previous post's videos belong to the page left
     * behind.
     *
     * <p>A feed does not trigger this: a tagged or dashboard page keeps one address while it
     * scrolls, so nothing here fires as the viewer moves through it, and the feed's videos
     * accumulate as they are found.
     */
    @Override
    public boolean resetOnRouteChange() {
        return true;
    }

    /**
     * The post named by the address, when the address names one.
     *
     * <p>Tumblr opens a post as a panel over the feed it was reached from. The address becomes
     * the post's own, but the feed is still there underneath with every one of its videos in the
     * page — so a scan finds the lot, and a viewer looking at a single post was shown nine.
     * Matching the id in the address against the id the extractor takes from the post's JSON
     * narrows that back to the post actually open.
     *
     * <p>A tagged or dashboard address carries no post id and returns null, so those pages go on
     * listing everything they find.
     */
    @Nullable
    @Override
    public String soloPostFor(String pageUrl) {
        if (pageUrl == null) return null;
        Matcher m = POST_ADDRESS.matcher(pageUrl);
        return m.find() ? "tmb:" + m.group(1) : null;
    }

    /**
     * True. Tumblr posts a real file for each video, so there is always something self-contained
     * to offer and no reason to send the user at a rendition ladder that has to be stitched back
     * together — which on this platform produced downloads that would not play.
     */
    @Override
    public boolean preferProgressive() {
        return true;
    }
}
