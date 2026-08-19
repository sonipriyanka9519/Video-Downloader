package com.ms.webview.ui;

import android.animation.ValueAnimator;
import android.content.Intent;
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

    /** The entrance, and the whole of how long this screen is up. Well inside the 1.5s ceiling. */
    private static final long HOLD_MS = 600L;

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
    private void showProgress() {
        ProgressBar bar = findViewById(R.id.splashProgress);
        bar.setVisibility(View.VISIBLE);
        bar.setAlpha(0f);
        bar.animate().alpha(1f).setDuration(FADE_MS).start();

        ValueAnimator fill = ValueAnimator.ofInt(0, 100);
        fill.setDuration(HOLD_MS);
        fill.setInterpolator(new DecelerateInterpolator());
        fill.addUpdateListener(a -> bar.setProgress((int) a.getAnimatedValue()));
        fill.start();
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
    private void openNext() {
        Intent next = HowTo.isSeen(this)
                ? new Intent(this, MainActivity.class)
                : WalkthroughActivity.firstRun(this);
        if (getIntent() != null && getIntent().getExtras() != null) {
            next.putExtras(getIntent().getExtras());
        }
        startActivity(next);
        // No reverse transition: this screen must not come back on Back.
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
