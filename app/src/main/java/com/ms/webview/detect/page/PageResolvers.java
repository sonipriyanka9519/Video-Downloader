package com.ms.webview.detect.page;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolvers that ask a platform about the current page directly.
 *
 * <p>Only for sites where listening to the page's own traffic is not enough. Everywhere else
 * the extractors do the job without an extra request.
 */
public final class PageResolvers {

    private static final List<PageResolver> ALL = Arrays.asList(
            new TwitterPageResolver(),
            new InstagramPageResolver(),
            new TwitchPageResolver(),
            new DailymotionPageResolver(),
            new TumblrPageResolver(),
            new FandomPageResolver());

    private PageResolvers() {
    }

    public static List<PageResolver> forUrl(String pageUrl) {
        List<PageResolver> applicable = new ArrayList<>(1);
        for (PageResolver resolver : ALL) {
            if (resolver.handles(pageUrl)) applicable.add(resolver);
        }
        return applicable;
    }
}
