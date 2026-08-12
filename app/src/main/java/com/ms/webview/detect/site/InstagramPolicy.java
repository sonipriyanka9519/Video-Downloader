package com.ms.webview.detect.site;

/**
 * Instagram.
 *
 * <p>Settings here are the ones that were working before any Facebook work began, plus the
 * playing-only rule. Nothing done for another platform may change this file.
 */
public class InstagramPolicy implements SitePolicy {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("instagram.com") || host.contains("cdninstagram.com");
    }

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /** Instagram rewrites the address for every reel, so the title must be paired with it. */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * False, and it must stay false.
     *
     * <p>Turning this on was a misreading: a page that is not a single-video scroll — a profile
     * or a post grid holding ten clips — has ten videos the viewer genuinely wants listed, and
     * requiring each to play first hid all of them. Multiple videos on one page are the normal
     * case here, not a feed artefact.
     */
    @Override
    public boolean playingOnly() {
        return false;
    }

    /**
     * True. Instagram shortcodes match on both sides, and the strictness is what stopped a
     * reel taking over the card of a neighbour that happened to run the same length.
     */
    @Override
    public boolean strictHintMatch() {
        return true;
    }
}
