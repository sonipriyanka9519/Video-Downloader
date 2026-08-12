package com.ms.webview.detect.site;

/**
 * Every host without a file of its own.
 *
 * <p>All of these are what the app did before platforms started needing to differ, so adopting
 * a policy for one site leaves the rest exactly as they were.
 */
public class DefaultPolicy implements SitePolicy {

    @Override
    public boolean appliesTo(String host) {
        return true;
    }

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_PAGE;
    }

    @Override
    public boolean playingOnly() {
        return false;
    }

    @Override
    public boolean strictHintMatch() {
        return false;
    }
}
