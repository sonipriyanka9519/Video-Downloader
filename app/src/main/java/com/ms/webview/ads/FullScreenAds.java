package com.ms.webview.ads;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Whether a full-screen ad is on screen right now.
 *
 * <p>One flag for both formats, because the failure it prevents needs only two of them and does
 * not care which. An app-open ad raised over an interstitial - or the reverse - leaves two ad
 * activities stacked, and the top one's close button dismisses it onto the other. From the outside
 * that is an ad that will not close.
 *
 * <p><b>The claim expires.</b> It is released on dismissal and on failure, but neither of those is
 * guaranteed to arrive: an ad activity finished by the system, a task swiped away, another screen
 * started over the top - any of them leaves the callback unfired and the flag set. Held forever,
 * that silently blocks every full-screen ad for the rest of the process, which presents as ads
 * that worked once and then never again.
 *
 * <p>So a claim older than {@link #MAX_HOLD_MS} is treated as abandoned. No real ad is on screen
 * that long without the viewer having dealt with it, and the cost of being wrong is one ad shown
 * over another rather than every ad suppressed.
 */
public final class FullScreenAds {

    /** Longer than any ad legitimately stays up. */
    private static final long MAX_HOLD_MS = 90_000L;

    private static volatile boolean showing;

    private static volatile long claimedAt;

    /** Whether something full-screen is over the app right now. */
    public static boolean busy() {
        return showing;
    }

    /**
     * Told when a full-screen ad covers the app, and when it goes.
     *
     * <p>For anything still making noise underneath one. An ad activity is translucent and lives in
     * this app's own task, so the screen behind it is paused but never stopped — and a page in the
     * browser with a video on it keeps playing, audibly, behind an advert the viewer cannot mute.
     *
     * <p>The activity lifecycle cannot be used for this. On the way back from the background the ad
     * is shown from ProcessLifecycleOwner's onStart, which lands between the browser's onStart and
     * its onResume: the page is resumed and starts playing a moment before the ad arrives on top
     * of it. Nothing about that sequence looks wrong from inside either half, which is why this is
     * announced explicitly rather than inferred.
     */
    public interface Watcher {
        void onFullScreenAd(boolean showing);
    }

    /** Copy-on-write: watchers register and unregister from view lifecycle, mid-iteration. */
    private static final CopyOnWriteArrayList<Watcher> watchers = new CopyOnWriteArrayList<>();

    public static void watch(Watcher watcher) {
        watchers.addIfAbsent(watcher);
    }

    public static void unwatch(Watcher watcher) {
        watchers.remove(watcher);
    }

    private static void announce(boolean showing) {
        for (Watcher watcher : watchers) watcher.onFullScreenAd(showing);
    }


    private FullScreenAds() {
    }

    /** True when nothing else has the screen and the caller may take it. */
    static synchronized boolean claim() {
        if (showing && System.currentTimeMillis() - claimedAt < MAX_HOLD_MS) return false;
        showing = true;
        claimedAt = System.currentTimeMillis();
        announce(true);
        return true;
    }

    /** Released on dismissal and on failure alike - a lock only ever taken is a lock. */
    static synchronized void release() {
        showing = false;
        announce(false);
    }
}
