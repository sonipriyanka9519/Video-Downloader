package com.ms.webview.detect.site;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pinterest.
 *
 * <p>Its own file, so what is set here reaches Pinterest and nothing else.
 */
public class PinterestPolicy implements SitePolicy {

    /**
     * The video's own name inside a pinimg address.
     *
     * <p>Pinterest files every rendition of one video under the same name and varies only the
     * directory above it — {@code /mc/720p/…/abc.mp4} beside {@code /mc/360p/…/abc.mp4} — and
     * sometimes adds a short rendition suffix, {@code abc_t1.mp4}. Both are stripped, so the
     * whole ladder answers to one key.
     *
     * <p>Anchored on the extension so it cannot fire on a thumbnail: {@code i.pinimg.com} paths
     * end in .jpg and yield nothing here.
     */
    private static final Pattern PINIMG_VIDEO_ID = Pattern.compile(
            "/([0-9a-zA-Z]{8,})(?:_[A-Za-z0-9]{1,6})?\\.(?:mp4|m3u8|ts)(?:\\?|$)",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean appliesTo(String host) {
        return host.contains("pinterest.") || host.contains("pinimg.com")
                || host.contains("pin.it");
    }

    /**
     * Newest first. A Pinterest board loads more as you scroll, so the pins turned up most
     * recently are the ones near where the viewer is now.
     */
    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Per URL. Opening a pin rewrites the address, and the document title follows a moment
     * later — pairing the two keeps a pin's name off the pin found during the gap.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * False. This was true for a while, on the reasoning that a board is a wall of pins nobody
     * asked for — but it depended on the playing video being recognised, and on Pinterest it is
     * not recognised reliably enough to carry that weight. A single unmatched video meant an
     * empty sheet, which is worse than a full one. Multiple videos are listed; narrowing to one
     * is done by {@link #resetOnRouteChange()} instead, which needs no such recognition.
     */
    @Override
    public boolean playingOnly() {
        return false;
    }

    /**
     * True. Opening a pin is a deliberate choice out of a grid, and the address changes with
     * it — so that is the moment to forget the board and look again at what is now on screen.
     *
     * <p>Safe here in a way it would not be on a feed: Pinterest changes the route when the
     * viewer opens something, not while they scroll past it, so this fires on the act rather
     * than on the movement.
     */
    @Override
    public boolean resetOnRouteChange() {
        return true;
    }

    /**
     * Groups a video's renditions by the name Pinterest gives the file itself.
     *
     * <p>Read from the address rather than from the payload, so it holds for a rendition
     * overheard on the wire with no JSON behind it — and so two renditions cannot end up on
     * separate cards merely because the payload described them in separate places.
     */
    @Nullable
    @Override
    public String groupKeyFor(String url) {
        if (url == null || !url.contains("pinimg.com")) return null;
        Matcher m = PINIMG_VIDEO_ID.matcher(url);
        return m.find() ? "pinv:" + m.group(1) : null;
    }

    /**
     * False. A pin id read from the address does not always name the video inside it — a pin
     * can carry an embed from somewhere else entirely — so refusing to look past the id would
     * mean never recognising what is playing.
     */
    @Override
    public boolean strictHintMatch() {
        return false;
    }
}
