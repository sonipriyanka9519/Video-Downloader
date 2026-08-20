package com.ms.webview.ads;

import com.ms.webview.BuildConfig;

/**
 * Every ad unit this app asks for, in one place.
 *
 * <p><b>A debug build can never request a real unit.</b> That is the whole reason this class exists
 * rather than the ids sitting inline at four call sites: a development run that fetches, shows and
 * accidentally clicks a live unit is invalid traffic against the account, and AdMob suspends for it.
 * The switch is on {@link BuildConfig#DEBUG}, so it cannot be forgotten.
 *
 * <p>The test values are Google's own published ids, which always fill and always serve a test
 * creative. The live ones are placeholders until the real account is wired up — see LIVE_APP_ID.
 */
public final class AdIds {

    /**
     * The AdMob application id, which also has to appear in the manifest.
     *
     * <p>The manifest cannot read a constant, so the value is repeated there as a literal. If this
     * changes, change it in both places — a mismatch makes the SDK throw on initialise, loudly and
     * immediately, which is at least the good kind of failure.
     */
    public static final String TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713";

    // TODO: replace with the real ids from the AdMob console before release.
        private static final String LIVE_APP_OPEN = "ca-app-pub-0000000000000000/0000000000";
    private static final String LIVE_INTERSTITIAL = "ca-app-pub-0000000000000000/0000000000";
    private static final String LIVE_NATIVE = "ca-app-pub-0000000000000000/0000000000";
    private static final String LIVE_BANNER = "ca-app-pub-0000000000000000/0000000000";

    private static final String TEST_APP_OPEN = "/21775744923/example/app-open";
    private static final String TEST_INTERSTITIAL = "/21775744923/example/interstitial";
    private static final String TEST_NATIVE = "/21775744923/example/native";
    private static final String TEST_BANNER = "/21775744923/example/fixed-size-banner";

    private AdIds() {
    }

    public static String appOpen() {
        return BuildConfig.DEBUG ? TEST_APP_OPEN : LIVE_APP_OPEN;
    }

    /**
     * The in-page ad, on the browser's home and in the downloads list.
     *
     * <p>One unit for both. They are the same placement in two screens - a card in a scrolling
     * page - and never on screen at the same time.
     */
    public static String nativeAd() {
        return BuildConfig.DEBUG ? TEST_NATIVE : LIVE_NATIVE;
    }

    /**
     * The fixed-size banner, on the lock screen and the tab sheet.
     *
     * <p>A banner rather than a native card in both places for the same reason: neither screen is a
     * list. There is no row language for a native ad to borrow, so one would read as a card the app
     * had invented rather than as part of the page - and a plain, obviously-bought rectangle is the
     * more honest thing on a screen whose own content is a keypad or a grid of tabs.
     */
    public static String banner() {
        return BuildConfig.DEBUG ? TEST_BANNER : LIVE_BANNER;
    }

    /** One unit for both interstitial placements: they never appear together. */
    public static String interstitial() {
        return BuildConfig.DEBUG ? TEST_INTERSTITIAL : LIVE_INTERSTITIAL;
    }

    /**
     * Whether the live ids have actually been filled in.
     *
     * <p>Nothing is requested in a release build that still carries the placeholders. A malformed
     * unit id fails every request anyway; failing quietly and locally is better than a release that
     * looks like it is serving ads and is not.
     */
    /** True for an Ad Manager unit path, which needs a different request than an AdMob id. */
    public static boolean isAdManager(String unitId) {
        return unitId != null && unitId.startsWith("/");
    }

    public static boolean configured() {
        return BuildConfig.DEBUG || !LIVE_INTERSTITIAL.startsWith("ca-app-pub-0000");
    }
}
