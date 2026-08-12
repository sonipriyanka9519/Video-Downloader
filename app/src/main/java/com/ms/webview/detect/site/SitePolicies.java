package com.ms.webview.detect.site;

import com.ms.webview.core.Formats;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Picks the policy for a host. First match wins; the default catches everything else. */
public final class SitePolicies {

    private static final List<SitePolicy> ALL = Arrays.asList(
            new InstagramPolicy(),
            new FacebookPolicy(),
            new DailymotionPolicy(),
            new PinterestPolicy(),
            new ImdbPolicy(),
            new TumblrPolicy(),
            new FlickrPolicy(),
            new FandomPolicy(),
            new VkPolicy(),
            new TwitchPolicy(),
            new SnapchatPolicy());

    private static final SitePolicy DEFAULT = new DefaultPolicy();

    private SitePolicies() {
    }

    public static SitePolicy forHost(String host) {
        if (host == null) return DEFAULT;
        String lower = host.toLowerCase(Locale.US);
        for (SitePolicy policy : ALL) {
            if (policy.appliesTo(lower)) return policy;
        }
        return DEFAULT;
    }

    /**
     * What this address is a view of, according to the site it belongs to, or null where the site
     * does not name its views. See {@link SitePolicy#routeKeyFor(String)}.
     */
    public static String routeKey(String url) {
        return url == null ? null : forHost(Formats.hostOf(url)).routeKeyFor(url);
    }
}
