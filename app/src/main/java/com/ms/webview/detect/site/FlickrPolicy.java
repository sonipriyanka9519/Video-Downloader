package com.ms.webview.detect.site;

/**
 * Flickr.
 *
 * <p>Its own file, so what is set here reaches Flickr and nothing else. Everything below is the
 * shared default except {@link #resetOnRouteChange()}, which is the one behaviour Flickr was
 * given a file for — the rest is stated plainly rather than inherited so that changing any of
 * it later is a local decision.
 */
public class FlickrPolicy implements SitePolicy {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("flickr.com") || host.contains("staticflickr.com")
                || host.contains("flickr.net");
    }

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Per URL. Flickr names the page after the photo or video open on it, and that title lands a
     * beat after the address changes — so pairing the two keeps one item's name off the next.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /** False. A photostream's videos are all things the viewer went looking for; list them. */
    @Override
    public boolean playingOnly() {
        return false;
    }

    @Override
    public boolean strictHintMatch() {
        return false;
    }

    /**
     * True — the reason this file exists. Moving to another photo or video rewrites the address,
     * and what was found on the page left behind is no longer what the viewer is looking at, so
     * the search starts again for the new one.
     *
     * <p>Only a change of path counts. Flickr appends and drops its own query parameters on a
     * page that has not otherwise moved, and treating that as navigation would clear the sheet
     * over and over on a page standing still.
     */
    @Override
    public boolean resetOnRouteChange() {
        return true;
    }
}
