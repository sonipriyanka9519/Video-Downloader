package com.ms.webview.ui.player;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.ms.webview.R;
import com.ms.webview.ui.Thumbnails;

/**
 * The pause between one video and the next — screen 09, panel D.
 *
 * <p>The design's rule, and it is the right one: never an unexplained auto-advance. So this shows
 * the real title of what is coming, counts down where it can be seen, and can be stopped by the
 * viewer or by any of the controls underneath it. Autoplay that cannot be interrupted is not a
 * convenience, it is a screen taking decisions on somebody's behalf.
 */
public final class UpNextCountdown {

    /** Long enough to read a title and reach the cancel; short enough not to feel like waiting. */
    private static final int SECONDS = 5;

    private static final int TICK_MS = 100;

    public interface Listener {
        /** The countdown ran out. Play what is next. */
        void onCountdownFinished();

        /** Stopped, by the viewer or by anything else that made it irrelevant. */
        void onCountdownCancelled();
    }

    private final View root;
    private final ImageView thumb;
    private final TextView title;
    private final TextView countdown;
    private final CircularProgressIndicator ring;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private long endsAt;
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;

            long left = endsAt - System.currentTimeMillis();
            if (left <= 0) {
                stop();
                listener.onCountdownFinished();
                return;
            }

            long total = SECONDS * 1000L;
            ring.setProgress((int) (100 - (left * 100 / total)));
            // Rounded up, so it reads 5, 4, 3, 2, 1 rather than spending a moment on 0 before
            // anything happens.
            countdown.setText(root.getContext().getString(
                    R.string.playing_in, (int) Math.ceil(left / 1000f)));
            handler.postDelayed(this, TICK_MS);
        }
    };

    public UpNextCountdown(@NonNull View root, @NonNull Listener listener) {
        this.root = root;
        this.listener = listener;
        thumb = root.findViewById(R.id.upNextThumb);
        title = root.findViewById(R.id.upNextTitle);
        countdown = root.findViewById(R.id.upNextCountdown);
        ring = root.findViewById(R.id.upNextRing);

        root.findViewById(R.id.btnUpNextCancel).setOnClickListener(v -> cancel());
        // Anywhere on the scrim, not only the word: somebody reaching to stop an auto-advance is
        // reaching quickly, and a 60dp target in the middle of a black screen is a poor bet.
        root.setOnClickListener(v -> cancel());
    }

    public boolean isRunning() {
        return running;
    }

    public void start(@NonNull PlayerQueue.Item next) {
        title.setText(next.title);
        if (TextUtils.isEmpty(next.posterUrl)) {
            thumb.setImageDrawable(null);
        } else {
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Thumbnails.load(thumb, next.posterUrl, null);
        }

        ring.setIndeterminate(false);
        ring.setMax(100);
        ring.setProgress(0);
        countdown.setText(root.getContext().getString(R.string.playing_in, SECONDS));

        root.setVisibility(View.VISIBLE);
        running = true;
        endsAt = System.currentTimeMillis() + SECONDS * 1000L;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    public void cancel() {
        if (!running) return;
        stop();
        listener.onCountdownCancelled();
    }

    /** Takes it down without telling anyone — for a screen going away under it. */
    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
        root.setVisibility(View.GONE);
    }
}
