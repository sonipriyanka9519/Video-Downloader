package com.ms.webview.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.splashscreen.SplashScreen;

import com.ms.webview.MainActivity;
import com.ms.webview.R;
import com.ms.webview.ui.onboard.Onboarding;
import com.ms.webview.ui.onboard.OnboardingActivity;

/**
 * The branded opening.
 *
 * <p>The system splash still runs first — it is what covers the gap between the icon being
 * tapped and this window existing, and dropping it would put a blank frame in front of the
 * brand. This activity picks the same crimson up and holds it while the animation plays.
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
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_splash);

        // Deliberately no inset padding: the gradient is meant to run under the status and
        // navigation bars, and nothing here sits close enough to either to be clipped.
        animateIn();
        handler.postDelayed(advance, HOLD_MS);
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
        Intent next = new Intent(this, Onboarding.isDone(this)
                ? MainActivity.class : OnboardingActivity.class);
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
