package com.ms.webview.detect.site;

/**
 * IMDb.
 *
 * <p>Its own file, so what is set here reaches IMDb and nothing else.
 */
public class ImdbPolicy implements SitePolicy {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("imdb.com") || host.contains("media-amazon.com");
    }

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Per URL. A title page is named after the film and a video page after the clip, and the
     * document title changes a beat after the address does — so a title taken for the site as a
     * whole would put the film's name on a trailer found during the gap.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * True. A title page lists every trailer, clip and featurette IMDb holds for the film, and
     * loads them whether or not anything is playing — so listing them all offers a dozen videos
     * where the viewer asked for one.
     *
     * <p>Safe here in a way it was not on Pinterest, and the difference is the id: IMDb writes
     * its vi-number into the address of the video being watched, and the extractor keys on the
     * same number, so the two meet. Pinterest had no such pairing, which is why the same
     * setting emptied the sheet there and had to be taken back out.
     */
    @Override
    public boolean playingOnly() {
        return true;
    }

    /**
     * True. The vi-number is exact and appears on both sides, so an id we do not hold means a
     * video not yet indexed rather than one of the others — and guessing on from a shared
     * running time is how a trailer takes over the card of the featurette beside it.
     *
     * <p>This only bites when an id is present and unknown. A title page carries no vi-number
     * at all, so playback there still falls through to the weaker signals as before.
     */
    @Override
    public boolean strictHintMatch() {
        return true;
    }

    /**
     * True. IMDb moves between videos by rewriting the address, and each is a deliberate
     * choice out of a list — so that is the moment to forget the previous one and look again.
     */
    @Override
    public boolean resetOnRouteChange() {
        return true;
    }
}
