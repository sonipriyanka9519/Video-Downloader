package com.ms.webview.ads;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdError;
import com.google.android.gms.ads.FullScreenContentCallback;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.admanager.AdManagerInterstitialAd;
import com.google.android.gms.ads.admanager.AdManagerInterstitialAdLoadCallback;
import com.google.android.gms.ads.interstitial.InterstitialAd;
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback;

/**
 * The full-screen ad, and the rules that keep it from being the app.
 *
 * <p>Two placements: after a download has been started from the detection sheet, and on the way out
 * of the player. Neither interrupts anything — the first comes after the work is handed off, the
 * second once the viewer has decided to leave.
 *
 * <p>The player placement is on the exit by explicit instruction, which is a departure from the
 * rule this app otherwise keeps of never showing an interstitial on a back press. It is noted here
 * rather than hidden: the error exits are excluded, both doors out behave the same, and the floor
 * below keeps a run of short videos from becoming a run of ads.
 *
 * <p><b>Shown every time, by explicit instruction.</b> There was a two-minute floor between
 * showings; it has been removed on request, so every download and every exit from the player shows
 * one if an ad is in hand.
 *
 * <p>That is a departure from the rule this app otherwise keeps — never twice in a row — and it is
 * written down here rather than left to be discovered. The mitigations that remain are the ones
 * that cost nothing: error exits never show one, the full-screen lock stops two stacking, and
 * {@code next} always runs so nothing the viewer asked for waits on an advert.
 */
public final class Interstitials {

    private static final String TAG = "Interstitials";

    @Nullable
    private static InterstitialAd ad;
    private static boolean loading;

    /**
     * When the in-flight request started, so a request that never returns cannot wedge this.
     *
     * <p>{@code loading} used to be a one-way latch: set before the request, cleared only by one of
     * the two callbacks. A request that returns neither — and the SDK's own HTTP timeout is sixty
     * seconds, so a saturated connection is exactly that — left it set for the life of the process,
     * and every later preload returned at the first line without asking for anything.
     *
     * <p>Which is why this failed only once a page was open in the browser. Detection puts real
     * traffic on the connection; one ad request stalls behind it, and from then on there is no ad
     * and no error, because nothing is ever requested again.
     */
    private static long loadingSince;

    /** Longer than the SDK's own timeout, so this is a backstop and not a second deadline. */
    private static final long LOAD_TIMEOUT_MS = 75_000L;

    /** Whether a request is genuinely still running, rather than one that never came back. */
    private static boolean loadInFlight() {
        if (!loading) return false;
        if (System.currentTimeMillis() - loadingSince <= LOAD_TIMEOUT_MS) return true;
        Log.w(TAG, "Interstitial request never returned; asking again");
        loading = false;
        return false;
    }


    /**
     * An ad owed to whichever screen comes next.
     *
     * <p>The player cannot show its own. Showing an interstitial and then finishing the activity
     * underneath it leaves the ad standing on a screen that is going away: the dismissal lands
     * nowhere, the task drops to the background instead of closing, and the player is still alive
     * and still playing when the app is reopened. Worse, the ad often has no close button, because
     * it is drawn over a host that has already stopped.
     *
     * <p>So the player closes first and marks this, and the screen it returns to shows the ad once
     * it is properly resumed. The order the viewer asked for - video closes, then the ad - and the
     * only order that actually works.
     */
    private static boolean owed;

    /** Called instead of showThen by a screen that is about to finish. */
    public static void queueForNextScreen() {
        owed = true;
    }

    /**
     * Pays what is owed, if anything.
     *
     * <p>Called from the screen the viewer lands on, in onResume - by which point it is the
     * resumed activity and can host a full-screen ad properly.
     *
     * @return whether an ad is actually being shown. A screen that finishes itself when something
     *         covers it - the private folder does, deliberately - has to know the difference
     *         between an ad it asked for and the viewer walking away, or it closes behind the ad
     *         and the dismissal lands somewhere else entirely.
     */
    /**
     * Skips the next payment without cancelling the debt.
     *
     * <p>For a flow that leaves the app and comes back through no choice of the viewer's. Making a
     * video private ends in the system's delete consent dialog, and its dismissal resumes the
     * library — so an ad owed from an earlier exit from the player landed there, on top of an
     * answer the viewer had just given. It reads as the ad being a consequence of confirming, which
     * is the one thing an ad next to a consent dialog must never look like.
     *
     * <p>Held rather than dropped: the debt is real and gets paid at the resume after this one.
     */
    public static void holdOffOnce() {
        holdOff = true;
    }

    private static boolean holdOff;

    public static boolean showIfQueued(@Nullable Activity activity) {
        if (!owed) return false;
        if (holdOff) {
            holdOff = false;
            return false;
        }

        // Discharged only when an ad actually appeared.
        //
        // Clearing it first was why this fired on some exits and not others: leave a video after a
        // second or two and the preload started in the player's onCreate has not landed yet, so
        // there is nothing to show — and the debt was thrown away along with the attempt. The next
        // exit had an ad and worked, which is what made it look random rather than early.
        //
        // Kept, the debt is paid at the next resume of one of these screens instead. Later than
        // asked for, but still a break between things rather than an interruption.
        if (!showThen(activity, () -> {
        })) {
            return false;
        }
        owed = false;
        return true;
    }

    private Interstitials() {
    }

    /**
     * Fetches one ahead of time.
     *
     * <p>Called when a screen that might show one opens, not when it wants to show one: an ad
     * requested at the moment of showing is an ad that arrives after the moment has passed.
     */
    public static void preload(Context context) {
        if (loadInFlight() || ad != null) return;
        // Entry, not just outcome. Without this a silent log is ambiguous: nothing was ever asked
        // for, or something was asked for and never answered. Those need different fixes.
        Log.i(TAG, "Interstitial requesting");
        // Through the gate, so a request made before consent finished is not simply dropped —
        // the same fix the banners needed, and the same reason ads appeared on some devices only.
        if (!Ads.ready()) {
            Context app = context.getApplicationContext();
            Ads.whenReady(() -> preload(app));
            return;
        }
        loading = true;
        loadingSince = System.currentTimeMillis();

        // Two loaders, chosen by the shape of the unit id.
        //
        // This is why no interstitial ever appeared. The unit is an Ad Manager path, and it was
        // being asked for with InterstitialAd and a plain AdRequest — the AdMob pair. That
        // combination does not fill an Ad Manager unit and does not report a failure either: the
        // request simply never comes back, so `loading` stayed true and nothing ever asked again.
        // "ad=false" in the log, on every exit from the player, for the whole session.
        //
        // The in-page ad had gone through Ads.requestFor since it was written and worked
        // throughout, which is the difference the log finally made visible.
        //
        // AdManagerInterstitialAd extends InterstitialAd, so only the loading differs — everything
        // below this point holds either one.
        final String unitId = AdIds.interstitial();
        // Wrapped because the failure being chased here is silence, and a throw from load() looks
        // exactly like one: the latch stays set and no callback ever arrives to clear it.
        try {
            if (AdIds.isAdManager(unitId)) {
                AdManagerInterstitialAd.load(context, unitId, Ads.adManagerRequest(),
                        new AdManagerInterstitialAdLoadCallback() {
                            @Override
                            public void onAdLoaded(@NonNull AdManagerInterstitialAd loaded) {
                                Log.i(TAG, "Interstitial ready");
                                ad = loaded;
                                loading = false;
                            }

                            @Override
                            public void onAdFailedToLoad(@NonNull LoadAdError error) {
                                Log.w(TAG, "Interstitial load failed: " + error.getMessage());
                                loading = false;
                            }
                        });
                return;
            }

            InterstitialAd.load(context, unitId, Ads.requestFor(unitId),
                    new InterstitialAdLoadCallback() {
                        @Override
                        public void onAdLoaded(@NonNull InterstitialAd loaded) {
                            Log.i(TAG, "Interstitial ready");
                            ad = loaded;
                            loading = false;
                        }

                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError error) {
                            Log.w(TAG, "Interstitial load failed: " + error.getMessage());
                            loading = false;
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "Interstitial load threw", t);
            loading = false;
        }
    }

    /**
     * Shows one if everything allows it, then runs {@code next}.
     *
     * <p><b>{@code next} always runs</b>, ad or no ad, exactly once. Every caller has something to
     * do afterwards — finish the activity, close the sheet — and an ad that failed must never be
     * the reason that does not happen.
     *
     * @return whether an ad is actually being shown
     */
    public static boolean showThen(@Nullable Activity activity, Runnable next) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || ad == null || !FullScreenAds.claim()) {
            // Which of the five it was, because "the ad did not show" has five causes and they
            // need different fixes: a dead host, nothing loaded yet, or another full-screen ad
            // still holding the lock.
            Log.i(TAG, "Interstitial skipped: host="
                    + (activity == null ? "null"
                    : activity.isFinishing() ? "finishing"
                    : activity.isDestroyed() ? "destroyed" : "ok")
                    + " ad=" + (ad != null) + " locked=" + FullScreenAds.busy());
            next.run();
            if (activity != null) preload(activity);
            return false;
        }

        InterstitialAd showable = ad;
        ad = null;
        // The next one is fetched now rather than on dismissal. Showing every time means the
        // gap between two of them can be seconds, and a request that only starts when the
        // current ad closes will not have finished by the time the next one is wanted.
        preload(activity);

        showable.setFullScreenContentCallback(new FullScreenContentCallback() {
            @Override
            public void onAdDismissedFullScreenContent() {
                FullScreenAds.release();
                next.run();
                preload(activity);
            }

            @Override
            public void onAdFailedToShowFullScreenContent(@NonNull AdError error) {
                Log.w(TAG, "Interstitial show failed: " + error.getMessage());
                // Released on failure too. A lock only ever taken is a lock.
                FullScreenAds.release();
                next.run();
                preload(activity);
            }
        });
        showable.show(activity);
        return true;
    }

}
