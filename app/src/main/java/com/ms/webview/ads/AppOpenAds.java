package com.ms.webview.ads;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ProcessLifecycleOwner;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.appopen.AppOpenAd;

/**
 * The ad shown as the app comes to the front.
 *
 * <p>Two moments, both the format's intended one: after the splash on a cold start, and again when
 * the app returns from the background. It is the one ad format designed for that instant — an
 * interstitial there would be the "on app open before content" that the AdMob rules forbid.
 *
 * <p><b>What it deliberately does not do.</b> On the automatic path it does not show over a
 * full-screen surface the viewer is in the middle of — the player and the app-lock screens exclude
 * themselves, because coming back to a paused film or a PIN pad is not an idle moment. It does not
 * show twice in one return, and it never shows the first time the app is ever opened.
 *
 * <p>That exclusion list applies to the automatic path only. The splash calls {@link #showThen}
 * deliberately, having already decided this is the moment — and it used to be on the list, so it
 * excluded itself every single time. Cold start showed nothing while returning from the background
 * worked perfectly, which is what made it look like a loading problem rather than a refusal.
 *
 * <p>An ad more than four hours old is thrown away rather than shown. AdMob expires them at that
 * point and a stale one either fails to render or reports nothing.
 */
public final class AppOpenAds implements Application.ActivityLifecycleCallbacks,
        DefaultLifecycleObserver {

    private static final String TAG = "AppOpenAds";

    /** AdMob expires an app-open ad after four hours. */
    private static final long MAX_AGE_MS = 4L * 60 * 60 * 1000;

    /**
     * Screens that are never interrupted.
     *
     * <p>By simple name so this file need not import them, and so a screen that moves package does
     * not silently drop off the list without anything failing.
     */
    /**
     * The only screens still excluded.
     *
     * <p>The splash is here for a different reason than the other two, and only the automatic
     * path is affected - showThen passes respectExclusions false, so the splash's own deliberate
     * ad still shows exactly as before.
     *
     * <p>What it stops is the second one. Leaving the app does not end the process: the task
     * finishes, ProcessLifecycleOwner reports a stop, and the next launch starts the splash with
     * seenFirstForeground already true and wentToBackground set. The return-from-background ad
     * then fired over the splash, and the splash showed its own straight after it - an ad, the
     * brand, and a second ad, in that order. The opening belongs to the splash; nothing else may
     * take it.
     *
     * <p>Both of the others are the PIN prompt. An ad thrown over somebody halfway through entering a PIN is not
     * an idle moment, and the private folder is the one place in this app where an interruption
     * has a cost beyond annoyance. Everything else - the player, the downloads list, About, a video
     * playing in the browser - now shows one on return, by request.
     */
    private static final String[] NEVER_OVER = {
            "AppLockActivity", "AppLockSetupActivity", "SplashActivity"
    };


    private static AppOpenAds instance;

    /** Always available, unlike the resumed activity. A load needs a Context, not a screen. */
    private Application app;

    @Nullable
    private AppOpenAd ad;
    @Nullable
    private Activity current;
    private boolean loading;
    /** Cleared by the callbacks, and by a resume that proves the ad has gone - see below. */
    private boolean showing;
    private long loadedAt;

    /** The first foreground of a process is the launch itself, which SplashActivity handles. */
    private boolean seenFirstForeground;

    /**
     * Whether the app genuinely went away, rather than merely being covered.
     *
     * <p>This is what makes the return reliable instead of "sometimes". It used to be gated on a
     * fifteen-second quiet window after any full-screen ad, which was a blunt way of stopping an
     * app-open ad appearing the instant an interstitial closed — and it also swallowed real trips
     * to the launcher and back inside that window.
     *
     * <p>The precise question is whether the process actually stopped, and the process lifecycle
     * answers it exactly: onStop only fires when nothing of this app is on screen at all. An ad
     * activity covering the app does not count, which is the case the window was guarding against.
     */
    private boolean wentToBackground;

    /** Run when the ad reaches the screen - see the three-argument showThen. */
    @Nullable
    private Runnable onShown;


    private AppOpenAds() {
    }

    public static void install(Application application) {
        if (instance != null) return;
        instance = new AppOpenAds();
        instance.app = application;
        application.registerActivityLifecycleCallbacks(instance);
        ProcessLifecycleOwner.get().getLifecycle().addObserver(instance);
    }

    /** Loads one ahead of time, so the moment it is wanted there is one to show. */
    public static void preload() {
        if (instance != null) instance.load();
    }

    /** Whether there is an ad in hand right now - the splash waits on this. */
    public static boolean hasOne() {
        return instance != null && instance.hasAd();
    }

    /**
     * Shows the ad if there is one, then runs {@code next} either way.
     *
     * <p>The splash calls this: it needs to move on whether an ad appeared or not, and it has
     * already decided this is the moment, so the exclusion list does not apply.
     *
     * <p>{@code next} always runs exactly once. An ad that failed, or never loaded, must never be
     * the reason the app does not open.
     */
    public static void showThen(Activity activity, Runnable next) {
        showThen(activity, null, next);
    }

    /**
     * The same, with a hook for the moment the ad is actually on screen.
     *
     * <p>{@code onShown} exists for the splash, and for one frame's worth of reason. The splash
     * used to blank itself immediately before asking for the ad, which left the brand gone while
     * the ad activity was still launching - an empty ground for a beat, then the ad, then an empty
     * ground again on the way to the next screen. Two blank frames around an advert.
     *
     * <p>Blanking once the ad is covering the screen removes the first, and the second goes with
     * it: when there is no ad, {@code onShown} never runs, so the brand simply stays up until the
     * next screen replaces it and there is no gap to see.
     */
    public static void showThen(Activity activity, @Nullable Runnable onShown, Runnable next) {
        if (instance == null) {
            next.run();
            return;
        }
        instance.onShown = onShown;
        instance.show(activity, next, false);
    }


    // ------------------------------------------------------------------ loading

    private void load() {
        if (loading || hasAd()) return;
        // Through the gate, so a request made before consent and the SDK finished is not simply
        // dropped — that was the other half of "no ad on the first open".
        if (!Ads.ready()) {
            Ads.whenReady(this::load);
            return;
        }
        loading = true;

        // The activity when there is one, the application when there is not. On a cold start this
        // runs before anything has resumed, and a null Context here is a load that never happens.
        // requestFor, not request. The unit here can be an Ad Manager path, and a plain
        // AdRequest sent to one does not fill - see the note in Ads.requestFor. The in-page ad has
        // always asked this way and always worked; this one did not, and that was the whole
        // difference between them.
        final String unitId = AdIds.appOpen();
        AppOpenAd.load(
                current != null ? current : app,
                unitId,
                Ads.requestFor(unitId),
                new AppOpenAd.AppOpenAdLoadCallback() {
                    @Override
                    public void onAdLoaded(@NonNull AppOpenAd loaded) {
                        ad = loaded;
                        loadedAt = System.currentTimeMillis();
                        loading = false;
                    }

                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        // No retry storm. The next foreground asks again, which is soon enough
                        // and costs nothing when there is no fill.
                        Log.w(TAG, "App-open load failed: " + error.getMessage());
                        loading = false;
                    }
                });
    }

    private boolean hasAd() {
        return ad != null && System.currentTimeMillis() - loadedAt < MAX_AGE_MS;
    }

    // ------------------------------------------------------------------ showing

    private void show(@Nullable Activity activity, @Nullable Runnable next,
                      boolean respectExclusions) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || !hasAd() || showing
                || (respectExclusions && excluded(activity))
                || !FullScreenAds.claim()) {
            onShown = null;
            if (next != null) next.run();
            // Nothing to show now, but there should be something next time.
            load();
            return;
        }

        AppOpenAd showable = ad;
        ad = null;
        showing = true;

        showable.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdShowedFullScreenContent() {
                Runnable shown = onShown;
                onShown = null;
                if (shown != null) shown.run();
            }

            @Override
            public void onAdDismissedFullScreenContent() {
                FullScreenAds.release();
                showing = false;
                if (next != null) next.run();
                load();
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError error) {
                Log.w(TAG, "App-open show failed: " + error.getMessage());
                FullScreenAds.release();
                showing = false;
                if (next != null) next.run();
                load();
            }
        });
        showable.show(activity);
    }

    private boolean excluded(Activity activity) {
        String name = activity.getClass().getSimpleName();
        for (String blocked : NEVER_OVER) {
            if (blocked.equals(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onStart(@NonNull LifecycleOwner owner) {
        // The first foreground is the launch. The splash owns that one, so that the ad lands after
        // the brand rather than on top of it.
        if (!seenFirstForeground) {
            seenFirstForeground = true;
            load();
            return;
        }

        // Only a real trip away and back. Without this the ad could fire on a lifecycle blip that
        // the viewer never experienced as leaving the app.
        if (!wentToBackground) return;
        wentToBackground = false;
        show(current, null, true);
    }

    @Override
    public void onStop(@NonNull LifecycleOwner owner) {
        // Nothing of this app is on screen. An ad activity on top does not reach here, which is
        // exactly the distinction that makes the return reliable.
        wentToBackground = true;
        // Asked for now, so there is one in hand by the time they come back rather than a request
        // starting at the moment it is needed.
        load();
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // One of this app's own screens is in front again, so no ad of ours is over it. If the
        // dismissal callback never arrived - the system finished the ad, the task was swiped away -
        // this is the proof, and without it the flag stayed set and no ad ever showed again.
        if (showing && !"AdActivity".equals(activity.getClass().getSimpleName())) {
            showing = false;
            FullScreenAds.release();
        }
        if (!showing) current = activity;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (!showing) current = activity;
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (current == activity) current = null;
    }
}
