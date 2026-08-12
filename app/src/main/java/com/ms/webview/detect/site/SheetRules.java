package com.ms.webview.detect.site;

import com.ms.webview.detect.MediaItem;

import java.util.Collection;
import java.util.List;

/**
 * What the sheet holds, and in what order — as one object per platform.
 *
 * <p>These two questions used to be answered by a run of {@code if} statements inside the
 * registry, reading a knob per behaviour: show only what is playing, keep what has been watched,
 * sort oldest-first or newest-first. Every platform's answer was assembled from the same code, so
 * a change made for one arrived at all of them, and the only way to tell whether Facebook still
 * worked after a week on Fandom was to open Facebook and look.
 *
 * <p>Now each platform's answer is a class. {@link DefaultSheetRules} is the one everything used
 * to share and still does unless a platform says otherwise, so adopting a class of your own
 * leaves every other site untouched — and editing that class cannot reach beyond the one site
 * that returns it. See {@link SitePolicy#sheetRules()}.
 */
public interface SheetRules {

    /**
     * Whether this video belongs in the sheet at all.
     *
     * <p>A video refused here is not counted as unresolved either: it is being left out because
     * of what it is, not because anything failed to read it.
     *
     * @param all everything currently held, for a rule that cannot decide on one video alone —
     *            "hide what is behind the viewer" has to know whether anything is ahead.
     */
    boolean admits(MediaItem item, Collection<MediaItem> all);

    /**
     * Arranges the admitted videos in place.
     *
     * <p>The whole list rather than a comparator, because the useful arrangements are not all
     * expressible as one. "The video on screen, then the ones coming, then the ones behind you"
     * is three groups: no pairwise test on a video's own fields can say whether it is the most
     * recently seen of a set it cannot see.
     */
    void sort(List<MediaItem> visible);
}
