package com.ms.webview.detect.page;

import androidx.annotation.Nullable;

import com.ms.webview.detect.extract.FoundMedia;

import java.util.List;

/**
 * Asks a platform directly what a page contains, instead of waiting to overhear it.
 *
 * <p>The extractors read responses the page happens to fetch while we are watching. That works
 * when a site keeps fetching as you scroll, but not when it loads everything in one request
 * before our hook is installed, and not when the response needs a session we do not have.
 * A resolver sidesteps both by calling a public endpoint itself.
 */
public interface PageResolver {

    /** Whether this resolver recognises the page the user is on. */
    boolean handles(String pageUrl);

    /**
     * The post id this page would produce, or null if it cannot be told from the URL alone.
     *
     * <p>Lets the registry skip the request entirely when the page's own traffic has already
     * yielded that post — which matters on a feed, where every scroll is a new route and
     * resolving each one would mean a network round trip per swipe.
     */
    @Nullable
    String hintFor(String pageUrl);

    /** Fetches and parses. Called on a background thread; may throw. */
    List<FoundMedia> resolve(String pageUrl, String userAgent) throws Exception;
}
