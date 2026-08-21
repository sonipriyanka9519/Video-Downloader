package com.ms.webview.ui;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ms.webview.ads.Ads;
import com.ms.webview.ads.AppOpenAds;
import com.ms.webview.MainActivity;
import com.ms.webview.R;
import com.ms.webview.ui.guide.HowTo;
import com.ms.webview.ui.guide.WalkthroughActivity;

/**
 * The opening — screen 14, panel A.
 *
 * <p>The system splash still runs first: it is what covers the gap between the icon being tapped and
 * this window existing, and dropping it would put a blank frame in front of the mark. This activity
 * picks up the same ground and holds it while the entrance plays, so the handover between the two is
 * not visible as a change of screen.
 *
 * <p><b>The wait is as short as the entrance and no longer.</b> A splash that lingers past its own
 * animation is a delay with a logo on it.
 *
 * <p>The design asks for a progress bar only when loading passes 600ms, capped at 1.5s. What is
 * measured here is the part that can be: how long the <em>process</em> took to reach this first
 * frame. Everything the app loads at startup — the database, the detection registry — happens before
 * this window exists, so by the time anything is drawn there is nothing left to wait for. A launch
 * that took longer than {@link #SLOW_MS} to get here was slow, and the bar says so for the length of
 * the hold; a launch that was quick never draws it at all.
 *
 * <p>Deliberately <em>not</em> stretched towards the 1.5s cap. The cap is a ceiling on how long
 * somebody may be kept here, not a target, and adding delay on the phones that were already slow is
 * the opposite of what the rule is for.
 */
public class SplashActivity extends AppCompatActivity {

    /**
     * Whether the opening has already been played in this process.
     *
     * <p>The brand and the ad belong to the launch, and the launch happens once. Everything that
     * can start the app from outside - a shared link, a tapped web link when this is the default
     * browser, a download notification - arrives at MainActivity rather than here, so those
     * openings skipped the splash and the ad with it. MainActivity checks this and sends the
     * intent back through here when it is false; see MainActivity.onCreate.
     *
     * <p>Static rather than stored, because it is a fact about this process and not about the
     * install: the next cold start should open properly again.
     */
    private static volatile boolean opened;

    /** True once the splash has handed over in this process. */
    public static boolean hasOpened() {
        return opened;
    }

    /** The entrance, and the whole of how long this screen is up. Well inside the 1.5s ceiling. */
    private static final long HOLD_MS = 100L;

    /** The brand again on the way out, after the ad and before the app. */
    private static final long AFTER_AD_MS = 00L;

    /** A launch slower than this had something to wait for, and gets told so. */
    private static final long SLOW_MS = 600L;

    private static final long FADE_MS = 380L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::openNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);
        // Left to follow the theme rather than forced light, which is what it used to be. The canvas
        // draws this screen in both, and the ground here is the app's own — a white opening in front
        // of a dark app is a flash of the wrong colour before anything has been said.
        setContentView(R.layout.activity_splash);

        keepClearOfNavigation();
        animateIn();
        if (startWasSlow()) showProgress();

        // Consent, then the SDK, then an ad fetched ready for the handover. All of it happens
        // while the brand is on screen, which is the only free time the app ever has - and the
        // form needs an activity to show itself on, so this is the first place it can run.
        Ads.start(this, AppOpenAds::preload);
        handler.postDelayed(advance, HOLD_MS);
    }

    /**
     * Holds the contents clear of the gesture bar.
     *
     * <p>Bottom only. The ornaments are meant to run under the status bar and nothing up there sits
     * close enough to be clipped; at the bottom the progress bar does — on a gesture-navigation phone
     * it landed behind the home pill.
     */
    private void keepClearOfNavigation() {
        View root = findViewById(R.id.splashRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(0, 0, 0, bars.bottom);
            return insets;
        });
    }

    /**
     * Whether getting this far took long enough to be worth explaining.
     *
     * <p>Measured from the process starting, not from this activity being created: the work that makes
     * a cold start slow — the database opening, the app's own setup — all runs before any of this is
     * drawn, so the only place the delay shows up is in how late this frame is.
     */
    private boolean startWasSlow() {
        long sinceProcessStart =
                SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime();
        return sinceProcessStart > SLOW_MS;
    }

    /**
     * Reveals the bar and fills it over exactly the time this screen has left.
     *
     * <p>It measures the only thing there is to measure — how much of the opening remains. A bar that
     * crept along and then jumped to full as the screen changed would be pretending to measure the
     * loading, which by this point has already happened.
     */
    /**
     * Raises the bar, once, and leaves it running.
     *
     * <p>Indeterminate rather than a single fill to 100. The wait is not a known length any more —
     * it ends when consent, the SDK and an ad have all finished, and how long that takes depends on
     * the network. A bar that fills to the end and stops while the app is still working says the
     * opposite of what is happening; a bar that keeps moving says "still going", which is true.
     *
     * <p>Called from more than one place and safe to call again: the second call does nothing, so
     * the animation is never restarted mid-way.
     */
    private void showProgress() {
        ProgressBar bar = findViewById(R.id.splashProgress);
        if (bar == null || bar.getVisibility() == View.VISIBLE) return;

        bar.setIndeterminate(true);
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(0f);
        bar.animate().alpha(1f).setDuration(FADE_MS).start();
    }

    private void animateIn() {
        View logo = findViewById(R.id.splashLogo);
        View name = findViewById(R.id.splashName);
        View tagline = findViewById(R.id.splashTagline);

        logo.setAlpha(0f);
        logo.setScaleX(0.7f);
        logo.setScaleY(0.7f);
        logo.animate()
                .alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(FADE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // The words follow the mark rather than arriving with it, which is what makes the sequence
        // read as one movement instead of a fade. Both land inside the hold.
        rise(name, 90L);
        rise(tagline, 150L);
    }

    private void rise(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(getResources().getDimensionPixelSize(R.dimen.ds_space_3));
        view.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(delay)
                .setDuration(FADE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * On to the walkthrough on a first launch, or straight to the app.
     *
     * <p>The extras travel either way, and they matter for one case in particular: a notification
     * Firebase displays itself opens the launcher activity — this one — with the message's payload in
     * its extras, so the link to the video is here and nowhere else. Passing the whole bundle on
     * rather than picking the link out of it keeps this screen ignorant of what a push contains,
     * which is the right amount for an opening animation to know.
     */
    /**
     * The app-open ad sits here, between the brand and the app.
     *
     * <p>After the splash rather than over it: the splash is the app introducing itself, and an ad
     * on top of that is the first thing anybody sees. If there is no ad - no consent, no fill, no
     * network - the handover happens immediately and nothing waits on it.
     *
     * <p>Not on a first run either. Somebody who has not seen the walkthrough has not seen the app.
     */
    /** At most this long waiting for an app-open ad before giving up and opening the app. */
    /**
     * At most this long waiting for an app-open ad before giving up and opening the app.
     *
     * <p>Six, not four. Measured on the emulator: consent has to resolve, then the SDK has to
     * start, and only then can the ad be requested — on a cold process that whole chain runs about
     * four to five seconds, so a four-second deadline expired just before the ad arrived every
     * single time. Foreground returns were unaffected, which is exactly why it looked like the
     * cold-start case was broken rather than merely early.
     */
    private static final long AD_WAIT_MS = 6000L;

    /**
     * Splash, then the ad, then whatever comes next - the walkthrough on a first run, the app on
     * every other. One order, no exceptions, because the ad belongs to the opening rather than to
     * the screen after it.
     */
    private void openNext() {
        waitForAd(System.currentTimeMillis());
    }

    /**
     * Holds the splash while the ad is still on its way, up to {@link #AD_WAIT_MS}.
     *
     * <p>This is why nothing appeared on the first open. Consent, the SDK and the ad request all
     * have to finish before there is anything to show, and on a cold start that is reliably longer
     * than the splash was holding for — so the handover always found an empty hand and went
     * straight through. It waits now, but only so long: an ad is never worth making somebody stare
     * at a logo, so four seconds later the app opens with or without one.
     */
    private void waitForAd(long since) {
        if (isFinishing() || isDestroyed()) return;

        if (AppOpenAds.hasOne() || System.currentTimeMillis() - since >= AD_WAIT_MS) {
            // The brand stays up throughout - behind the ad, and again for a beat after it.
            //
            // It used to be hidden the moment the ad was asked for, so that closing the ad
            // uncovered bare background rather than the logo a second time. That left an empty
            // ground for the beat the ad activity takes to launch, and a blank screen all the way
            // to the next activity whenever no ad arrived at all.
            //
            // Kept, there is nothing blank to see at any point: the splash is what is underneath
            // the ad, and what is left when it closes.
            final boolean[] adShown = {false};
            AppOpenAds.showThen(this, () -> adShown[0] = true, () -> {
                // The pause belongs to the ad. Without one, the app arrives in the same frame
                // the ad closes and the whole opening reads as a stumble; with one when no ad
                // showed, the splash simply sits there a second longer for no reason.
                if (adShown[0]) handler.postDelayed(this::handOver, AFTER_AD_MS);
                else handOver();
            });
            return;
        }
        // A splash that sits still for six seconds reads as a hang. The bar is already built for
        // exactly this - see startWasSlow - so it is raised here too rather than invented again.
        showProgress();
        handler.postDelayed(() -> waitForAd(since), 150L);
    }

    private void handOver() {
        // The activity can be gone by the time an ad is dismissed.
        if (isFinishing() || isDestroyed()) return;
        Intent next = HowTo.isSeen(this)
                ? new Intent(this, MainActivity.class)
                : WalkthroughActivity.firstRun(this);
        // The whole intent, not only its extras.
        //
        // A shared link is ACTION_SEND carrying EXTRA_TEXT, but a tapped web link is ACTION_VIEW
        // with a data uri - and a uri is not an extra. Copying extras alone would carry a
        // notification's payload through and drop every link on the floor.
        Intent from = getIntent();
        if (from != null) {
            if (from.getAction() != null) next.setAction(from.getAction());
            if (from.getData() != null) next.setDataAndType(from.getData(), from.getType());
            if (from.getExtras() != null) next.putExtras(from.getExtras());
        }
        // Set before the handover, so the screen we start does not send us straight back here.
        opened = true;
        startActivity(next);

        // No reverse transition: this screen must not come back on Back.
        finish();
        // And no forward one either, now that an ad can come between. A cross-fade here played the
        // splash a second time after the viewer had already sat through it.
        //
        // Two calls because the old one stopped working: overridePendingTransition is deprecated
        // from API 34 and simply ignored, which is every device this was tested on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0);
        } else {
            overridePendingTransition(0, 0);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
