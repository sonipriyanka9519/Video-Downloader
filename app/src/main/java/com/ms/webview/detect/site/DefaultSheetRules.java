package com.ms.webview.detect.site;

import com.ms.webview.detect.MediaItem;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The sheet every platform had before any of them had one of its own.
 *
 * <p>A faithful copy of what the registry used to do inline, reading the same knobs off the same
 * policy — so a site that has not adopted rules of its own behaves exactly as it always has.
 * Nothing platform-specific belongs here; that is what a class of your own is for.
 */
public class DefaultSheetRules implements SheetRules, Comparator<MediaItem> {

    private final SitePolicy policy;

    public DefaultSheetRules(SitePolicy policy) {
        this.policy = policy;
    }

    /**
     * Sorted by the comparator below, and the badge follows the playing mark exactly — which is
     * what every platform did before any of them had rules of its own.
     */
    @Override
    public void sort(List<MediaItem> visible) {
        for (MediaItem item : visible) item.current = item.playing;
        Collections.sort(visible, this);
    }

    /**
     * Everything, unless the platform admits only what has been on screen — in which case a video
     * the page merely pre-loaded is not yet a video the viewer has.
     */
    @Override
    public boolean admits(MediaItem item, Collection<MediaItem> all) {
        if (!policy.playingOnly()) return true;
        return item.playing || (item.watched && policy.keepWatched());
    }

    @Override
    public int compare(MediaItem a, MediaItem b) {
        // The video on screen first.
        if (a.playing != b.playing) return a.playing ? -1 : 1;

        // Then anything not yet watched, ahead of everything already scrolled past. This is what
        // stops the card next to the current one being a clip just finished with: swiping across
        // the sheet should move forward through the feed, not back over it. Watched videos keep
        // their place at the end rather than disappearing — one of them may still be the video
        // worth saving.
        if (a.watched != b.watched) return a.watched ? 1 : -1;

        int da = a.hasDownloadable() ? 0 : 1;
        int db = b.hasDownloadable() ? 0 : 1;
        if (da != db) return Integer.compare(da, db);

        return policy.order() == SitePolicy.Order.FEED
                ? Long.compare(a.discoveredAt, b.discoveredAt)
                : Long.compare(b.discoveredAt, a.discoveredAt);
    }
}
