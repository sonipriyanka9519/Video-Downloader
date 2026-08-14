package com.ms.webview.ui;

import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ms.webview.MainActivity;
import com.ms.webview.R;

/**
 * The opening.
 *
 * <p>The system splash still runs first — it is what covers the gap between the icon being tapped
 * and this window existing, and dropping it would put a blank frame in front of the mark. This
 * activity picks up the same white and holds it while the animation plays, so the handover
 * between the two is not visible as a change of screen.
 *
 * <p>The wait is the length of that animation and nothing more. A splash that lingers past its
 * own transition is just a delay with a logo on it.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long HOLD_MS = 1150L;
    private static final long FADE_MS = 520L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advance = this::openNext;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);
        // Forced light rather than left to follow the device. The default adapts the status-bar
        // icons to the system's dark-mode setting, and this screen is white whatever that setting
        // says — on a phone in dark mode the icons would come out white on white.
        EdgeToEdge.enable(this,
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT));
        setContentView(R.layout.activity_splash);

        keepClearOfNavigation();
        animateIn();
        runProgress();
        handler.postDelayed(advance, HOLD_MS);
    }

    /**
     * Holds the screen's contents clear of the gesture bar.
     *
     * <p>Bottom only. The top is left alone deliberately: the ornaments are meant to run under the
     * status bar, and nothing up there sits close enough to be clipped. At the bottom the progress
     * bar does — on a gesture-navigation phone it landed behind the home pill.
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
     * Fills the bar over exactly the time this screen is on show.
     *
     * <p>Tied to the wait rather than to any work, because there is no work — the app is already
     * running by the time this is drawn. A bar that crept along and then jumped to full when the
     * screen changed would be pretending to measure something; this one measures the only thing
     * there is to measure, which is how much of the opening is left.
     */
    private void runProgress() {
        ProgressBar bar = findViewById(R.id.splashProgress);

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

        // The words follow the mark rather than arriving with it, which is what makes the
        // sequence read as one movement instead of a fade.
        rise(name, 120L);
        rise(tagline, 200L);
    }

    private void rise(View view, long delay) {
        view.setAlpha(0f);
        view.setTranslationY(getResources()
                .getDimensionPixelSize(com.intuit.sdp.R.dimen._12sdp));
        view.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(delay)
                .setDuration(FADE_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /**
     * On to the app, carrying anything the launch arrived with.
     *
     * <p>The extras matter for one case in particular. A notification Firebase displays itself
     * opens the launcher activity — this one — with the message's payload in its extras, so the
     * link to the video is here and nowhere else. Passing the whole bundle on rather than picking
     * the link out of it keeps this screen ignorant of what a push contains, which is the right
     * amount for an opening animation to know.
     */
    private void openNext() {
        Intent next = new Intent(this, MainActivity.class);
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
        handler.removeCallbacks(advance);
        super.onDestroy();
    }
}
