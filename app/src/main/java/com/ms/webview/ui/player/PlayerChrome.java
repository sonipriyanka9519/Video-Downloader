package com.ms.webview.ui.player;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.google.android.material.slider.Slider;
import com.ms.webview.R;
import com.ms.webview.core.Formats;

/**
 * The player's overlay — screen 09, panels A, B, E and F.
 *
 * <p>Its own class rather than more of {@link com.ms.webview.ui.PlayerActivity}, which already
 * carries the parts that took the longest to get right: decoder fallback, the error mapping, the
 * track logging. None of that wants to be read past to find a gesture handler.
 *
 * <p>The design's central claim is that every control has a gesture twin, and that is what earns
 * the overlay the right to disappear after three seconds. So the two are built together here:
 * the button and the gesture that does the same thing sit in the same file, and neither can be
 * added without the other being obvious by its absence.
 */
public final class PlayerChrome {

    /** What the design asks for: the chrome goes after three seconds of being ignored. */
    private static final long HIDE_AFTER_MS = 3000L;
    private static final long FADE_MS = 200L;

    /** How far a double-tap jumps, matching the two buttons either side of play. */
    private static final long SEEK_STEP_MS = 10_000L;

    /** How long the bloom under a double-tap stays up. Long enough to see, short enough to miss. */
    private static final long FEEDBACK_MS = 600L;

    /** Held speed, per the design. One value, because a held gesture is not a fine control. */
    private static final float HELD_SPEED = 2f;

    /** The tap-to-cycle ladder. Long-press opens the full list instead. */
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    /** A vertical drag this long crosses the whole range, so the gesture is the same everywhere. */
    private static final float DRAG_RANGE_FRACTION = 0.7f;

    /** Below this a drag is a tap that wobbled, not a gesture. */
    private static final int DRAG_SLOP_PX = 24;

    /** Everything the overlay cannot decide for itself. */
    public interface Host {
        void onBack();

        void onRotate();

        void onAspect();

        void onPictureInPicture();

        void onOverflow(View anchor);

        void onSpeedList();

        void onQueue();

        void onPrevious();

        void onNext();

        /** True when there is more than one video in hand — the queue controls hang off it. */
        boolean hasQueue();
    }

    private final Activity activity;
    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final View controls;
    private final View topBar;
    private final View bottomBar;
    private final TextView title;
    private final TextView elapsed;
    private final TextView duration;
    private final Slider seek;
    private final ImageButton play;
    private final TextView speedChip;
    private final View queueButton;
    private final TextView queueCount;

    private final LinearLayout seekFeedback;
    private final ImageView seekFeedbackIcon;
    private final TextView seekFeedbackLabel;
    private final View brightnessRail;
    private final View volumeRail;
    private final View speedBadge;
    private final TextView speedBadgeLabel;

    /**
     * The padding the two bars were inflated with, before any inset was added to it.
     *
     * <p>Captured once, because insets arrive again on every rotation and the only safe base to
     * add them to is the one from the layout.
     */
    private final int baseTopBarLeft;
    private final int baseTopBarRight;
    private final int baseBottomBarLeft;
    private final int baseBottomBarRight;
    private final int baseBottomBarBottom;

    /** Panel F. Only ever visible when the file turns out to have no picture in it. */
    private final View audioPanel;
    private final TextView audioTitle;
    private final TextView audioMeta;
    private final View rotate;
    private final View aspect;

    @Nullable
    private Player player;
    private boolean shown = true;
    /** True while a finger is on the seek bar, so the ticker stops fighting it. */
    private boolean scrubbing;
    private float speed = 1f;
    /** What the speed was before a long-press took it to 2x. */
    private float speedBeforeHold = 1f;
    private boolean holding;

    /** Which half of the screen the current vertical drag started on, and where. */
    private boolean draggingLeft;
    private boolean dragging;
    private float dragStartY;
    private float dragStartValue;

    /**
     * Method references, not lambdas over the fields.
     *
     * <p>Field initialisers run before the constructor body, so a lambda here that read
     * {@code seekFeedback} would be reading a blank final the compiler cannot prove is assigned.
     * A reference to a method on {@code this} defers the read to the moment it runs, which is
     * always after the constructor has finished.
     */
    private final Runnable hide = this::hide;
    private final Runnable clearFeedback = this::hideSeekFeedback;
    private final Runnable clearRails = this::hideRails;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            syncProgress();
            // Twice a second. A timecode that only ticks on the second looks stuck at the
            // moment somebody is watching it to decide whether to seek.
            handler.postDelayed(this, 500L);
        }
    };

    public PlayerChrome(@NonNull Activity activity, @NonNull View root, @NonNull Host host) {
        this.activity = activity;
        this.host = host;

        controls = root.findViewById(R.id.playerControls);
        topBar = root.findViewById(R.id.playerTopBar);
        bottomBar = root.findViewById(R.id.playerBottomBar);
        title = root.findViewById(R.id.playerTitle);
        elapsed = root.findViewById(R.id.playerElapsed);
        duration = root.findViewById(R.id.playerDuration);
        seek = root.findViewById(R.id.playerSeek);
        play = root.findViewById(R.id.btnPlayerPlay);
        speedChip = root.findViewById(R.id.btnPlayerSpeed);
        queueButton = root.findViewById(R.id.btnPlayerQueue);
        queueCount = root.findViewById(R.id.playerQueueCount);

        seekFeedback = root.findViewById(R.id.seekFeedback);
        seekFeedbackIcon = root.findViewById(R.id.seekFeedbackIcon);
        seekFeedbackLabel = root.findViewById(R.id.seekFeedbackLabel);
        brightnessRail = root.findViewById(R.id.brightnessRail);
        volumeRail = root.findViewById(R.id.volumeRail);
        speedBadge = root.findViewById(R.id.speedBadge);
        speedBadgeLabel = root.findViewById(R.id.speedBadgeLabel);

        audioPanel = root.findViewById(R.id.audioPanel);
        audioTitle = root.findViewById(R.id.audioTitle);
        audioMeta = root.findViewById(R.id.audioMeta);
        rotate = root.findViewById(R.id.btnPlayerRotate);
        aspect = root.findViewById(R.id.btnPlayerAspect);

        // Read before anything has had a chance to add an inset to them.
        baseTopBarLeft = topBar.getPaddingLeft();
        baseTopBarRight = topBar.getPaddingRight();
        baseBottomBarLeft = bottomBar.getPaddingLeft();
        baseBottomBarRight = bottomBar.getPaddingRight();
        baseBottomBarBottom = bottomBar.getPaddingBottom();

        wireButtons(root);
        wireSeekBar();
        wireGestures(root);
        setSpeed(1f);
    }

    // ------------------------------------------------------------------ lifecycle

    public void attach(@Nullable Player player) {
        this.player = player;
        syncProgress();
        syncPlayIcon();
        handler.removeCallbacks(tick);
        handler.post(tick);
        show();
    }

    public void detach() {
        player = null;
        handler.removeCallbacksAndMessages(null);
    }

    public void setTitle(@Nullable CharSequence text) {
        title.setText(text);
    }

    /** Hidden entirely in picture-in-picture: the window is a thumbnail, not a place to press. */
    public void setChromeAllowed(boolean allowed) {
        controls.setVisibility(allowed ? View.VISIBLE : View.GONE);
        if (allowed) show();
    }

    /**
     * A sound file — screen 09, panel F.
     *
     * <p>The same player throughout: transport, seek bar, speed chip and every gesture mean
     * exactly what they meant for video. Three things move. The art tile takes the place of the
     * picture; the name moves out of the top bar and under the tile, because with no picture to
     * caption it is the thing on screen rather than a label over it; and rotate and aspect go,
     * since neither has anything to act on.
     */
    public void setAudio(boolean audio, @Nullable CharSequence name, @Nullable CharSequence meta) {
        audioPanel.setVisibility(audio ? View.VISIBLE : View.GONE);
        title.setVisibility(audio ? View.INVISIBLE : View.VISIBLE);
        rotate.setVisibility(audio ? View.GONE : View.VISIBLE);
        aspect.setVisibility(audio ? View.GONE : View.VISIBLE);
        if (!audio) return;

        audioTitle.setText(name);
        audioMeta.setText(meta);
        // The overlay stops hiding: with nothing moving on screen there is no picture for it to
        // be in the way of, and a sound file with no visible controls is a black rectangle.
        handler.removeCallbacks(hide);
        if (!shown) show();
        handler.removeCallbacks(hide);
    }

    public void setQueueCount(int count) {
        boolean any = count > 0;
        queueButton.setVisibility(any ? View.VISIBLE : View.GONE);
        if (any) queueCount.setText(String.valueOf(count));
    }

    // ------------------------------------------------------------------ showing and hiding

    public void show() {
        handler.removeCallbacks(hide);
        if (!shown) {
            shown = true;
            fade(controls, 1f);
        }
        // Rearmed on every show, including one that was already showing — touching the screen is
        // the viewer saying they are still here.
        handler.postDelayed(hide, HIDE_AFTER_MS);
    }

    public void hide() {
        handler.removeCallbacks(hide);
        if (!shown) return;
        shown = false;
        fade(controls, 0f);
    }

    private void toggle() {
        if (shown) hide();
        else show();
    }

    /**
     * Fades rather than snaps, unless the device has been told not to animate.
     *
     * <p>Honouring the animator duration scale is not decoration here: at zero the system is
     * saying somebody finds motion uncomfortable or is running on a device where it costs, and a
     * fading overlay is exactly the kind of thing that setting is about.
     */
    private void fade(View view, float alpha) {
        boolean visible = alpha > 0f;
        if (visible) view.setVisibility(View.VISIBLE);

        if (animationScale() == 0f) {
            view.setAlpha(alpha);
            view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        view.animate().alpha(alpha).setDuration(FADE_MS)
                .withEndAction(() -> view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE))
                .start();
    }

    private float animationScale() {
        return Settings.Global.getFloat(activity.getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    // ------------------------------------------------------------------ controls

    private void wireButtons(View root) {
        root.findViewById(R.id.btnPlayerBack).setOnClickListener(v -> host.onBack());
        rotate.setOnClickListener(v -> {
            host.onRotate();
            show();
        });
        aspect.setOnClickListener(v -> {
            host.onAspect();
            show();
        });
        root.findViewById(R.id.btnPlayerPip).setOnClickListener(v -> host.onPictureInPicture());
        root.findViewById(R.id.btnPlayerMore).setOnClickListener(host::onOverflow);

        play.setOnClickListener(v -> {
            togglePlayback();
            show();
        });
        root.findViewById(R.id.btnPlayerRewind).setOnClickListener(v -> {
            seekBy(-SEEK_STEP_MS);
            show();
        });
        root.findViewById(R.id.btnPlayerForward).setOnClickListener(v -> {
            seekBy(SEEK_STEP_MS);
            show();
        });

        // Queue controls do nothing until there is a queue, so they say so by not being there.
        View prev = root.findViewById(R.id.btnPlayerPrev);
        View next = root.findViewById(R.id.btnPlayerNext);
        boolean queued = host.hasQueue();
        prev.setVisibility(queued ? View.VISIBLE : View.GONE);
        next.setVisibility(queued ? View.VISIBLE : View.GONE);
        prev.setOnClickListener(v -> {
            host.onPrevious();
            show();
        });
        next.setOnClickListener(v -> {
            host.onNext();
            show();
        });

        queueButton.setOnClickListener(v -> host.onQueue());

        // Tap cycles, long-press opens the list — the design's own split, and the right one:
        // the ladder is short enough to walk and long enough to want to skip.
        speedChip.setOnClickListener(v -> {
            setSpeed(nextSpeed());
            show();
        });
        speedChip.setOnLongClickListener(v -> {
            host.onSpeedList();
            return true;
        });
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.isPlaying()) player.pause();
        else player.play();
        syncPlayIcon();
    }

    private void seekBy(long deltaMs) {
        if (player == null) return;
        long target = Math.max(0, player.getCurrentPosition() + deltaMs);
        long end = player.getDuration();
        if (end > 0) target = Math.min(target, end);
        player.seekTo(target);
        syncProgress();
    }

    private float nextSpeed() {
        for (float option : SPEEDS) {
            if (option > speed + 0.01f) return option;
        }
        return SPEEDS[0];
    }

    public void setSpeed(float value) {
        speed = value;
        if (player != null) player.setPlaybackSpeed(value);
        speedChip.setText(activity.getString(R.string.speed_label, Formats.speedLabel(value)));
    }

    public float speed() {
        return speed;
    }

    public float[] speeds() {
        return SPEEDS;
    }

    // ------------------------------------------------------------------ the seek bar

    private void wireSeekBar() {
        seek.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                scrubbing = true;
                // Held open while a finger is on it: a bar that vanished mid-scrub would take
                // the timecode the scrub is aiming at with it.
                handler.removeCallbacks(hide);
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                scrubbing = false;
                seekToFraction(slider.getValue() / slider.getValueTo());
                show();
            }
        });

        // Dragging updates the timecode as it goes, so the number leads the finger rather than
        // reporting where it ended up.
        seek.addOnChangeListener((slider, value, fromUser) -> {
            if (!fromUser || player == null) return;
            long total = player.getDuration();
            if (total > 0) {
                elapsed.setText(Formats.duration((long) (total * value / slider.getValueTo())));
            }
        });
    }

    private void seekToFraction(float fraction) {
        if (player == null) return;
        long total = player.getDuration();
        if (total <= 0) return;
        player.seekTo((long) (total * Math.max(0f, Math.min(1f, fraction))));
    }

    public void syncPlayIcon() {
        boolean playing = player != null && player.isPlaying();
        play.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(activity.getString(playing ? R.string.pause : R.string.play));
    }

    private void syncProgress() {
        if (player == null) return;

        long position = Math.max(0, player.getCurrentPosition());
        long total = player.getDuration();

        if (!scrubbing) {
            elapsed.setText(Formats.duration(position));
            // A live or still-loading stream has no duration to divide by, so the bar sits at
            // zero rather than jumping about as the estimate settles.
            float fraction = total > 0 ? Math.min(1f, position / (float) total) : 0f;
            seek.setValue(fraction * seek.getValueTo());
        }
        duration.setText(total > 0 ? Formats.duration(total) : "");
        syncPlayIcon();
    }

    // ------------------------------------------------------------------ gestures

    /**
     * The gesture twin of every control above.
     *
     * <p>Attached to the root rather than to the video, so the whole window responds — including
     * the letterboxed black either side of a portrait video, which is where a thumb naturally
     * falls on a phone held one-handed.
     */
    private void wireGestures(View root) {
        GestureDetector detector = new GestureDetector(activity,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                        toggle();
                        return true;
                    }

                    @Override
                    public boolean onDoubleTap(@NonNull MotionEvent e) {
                        boolean back = e.getX() < root.getWidth() / 2f;
                        seekBy(back ? -SEEK_STEP_MS : SEEK_STEP_MS);
                        showSeekFeedback(back);
                        return true;
                    }

                    @Override
                    public void onLongPress(@NonNull MotionEvent e) {
                        beginHeldSpeed();
                    }
                });
        detector.setIsLongpressEnabled(true);

        root.setOnTouchListener((v, event) -> {
            boolean handled = detector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dragging = false;
                    dragStartY = event.getY();
                    draggingLeft = event.getX() < v.getWidth() / 2f;
                    break;
                case MotionEvent.ACTION_MOVE:
                    onDrag(v, event);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    endHeldSpeed();
                    if (dragging) {
                        dragging = false;
                        // Left up for a moment after the finger goes, so the last value can be
                        // read — taking it away at the instant of release hides the answer.
                        handler.removeCallbacks(clearRails);
                        handler.postDelayed(clearRails, FEEDBACK_MS);
                    }
                    break;
                default:
                    break;
            }
            return handled || true;
        });
    }

    private void onDrag(View root, MotionEvent event) {
        float dy = dragStartY - event.getY();
        if (!dragging) {
            if (Math.abs(dy) < DRAG_SLOP_PX) return;
            dragging = true;
            dragStartValue = draggingLeft ? currentBrightness() : currentVolume();
            handler.removeCallbacks(clearRails);
            // A drag is the viewer working, not idling — the chrome should not fade under it.
            handler.removeCallbacks(hide);
        }

        float range = Math.max(1f, root.getHeight() * DRAG_RANGE_FRACTION);
        float value = Math.max(0f, Math.min(1f, dragStartValue + dy / range));
        if (draggingLeft) applyBrightness(value);
        else applyVolume(value);
    }

    private void hideSeekFeedback() {
        seekFeedback.setVisibility(View.GONE);
    }

    private void hideRails() {
        brightnessRail.setVisibility(View.GONE);
        volumeRail.setVisibility(View.GONE);
    }

    /** −10s or +10s, blooming on the half of the screen that was tapped. */
    private void showSeekFeedback(boolean back) {
        seekFeedbackIcon.setImageResource(back
                ? R.drawable.ic_replay_10 : R.drawable.ic_forward_10);
        seekFeedbackLabel.setText(activity.getString(back
                ? R.string.seek_back_10 : R.string.seek_forward_10));

        // FrameLayout params, not LinearLayout: this view is a LinearLayout, but its layout
        // params belong to its parent, and its parent is the player's FrameLayout root.
        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) seekFeedback.getLayoutParams();
        params.gravity = (back ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL;
        seekFeedback.setLayoutParams(params);

        seekFeedback.setVisibility(View.VISIBLE);
        handler.removeCallbacks(clearFeedback);
        handler.postDelayed(clearFeedback, FEEDBACK_MS);
    }

    // ------------------------------------------------------------------ held speed

    private void beginHeldSpeed() {
        if (player == null || holding) return;
        holding = true;
        speedBeforeHold = speed;
        setSpeed(HELD_SPEED);
        speedBadgeLabel.setText(activity.getString(R.string.speed_label,
                Formats.speedLabel(HELD_SPEED)));
        speedBadge.setVisibility(View.VISIBLE);
    }

    private void endHeldSpeed() {
        if (!holding) return;
        holding = false;
        // Back to whatever it was, not to 1x: somebody watching at 1.5x who holds to skip a dull
        // stretch expects to be returned to 1.5x, not corrected.
        setSpeed(speedBeforeHold);
        speedBadge.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ brightness and volume

    /**
     * Brightness is set on the window, never on the device.
     *
     * <p>A player that changed the system setting would leave the phone dimmed after the video
     * ended, and would need a permission to do it. The window override lasts exactly as long as
     * this screen is in front, which is the whole of what anybody means by the gesture.
     */
    private float currentBrightness() {
        float current = activity.getWindow().getAttributes().screenBrightness;
        if (current >= 0f) return current;

        // BRIGHTNESS_OVERRIDE_NONE: the window is following the system, so start from there
        // rather than from an arbitrary half, or the first drag would jump.
        try {
            int system = Settings.System.getInt(activity.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS);
            return Math.max(0f, Math.min(1f, system / 255f));
        } catch (Settings.SettingNotFoundException e) {
            return 0.5f;
        }
    }

    private void applyBrightness(float value) {
        WindowManager.LayoutParams params = activity.getWindow().getAttributes();
        // Never fully black: a screen dragged to zero looks like the app has crashed, and the
        // gesture that got there is no longer visible to undo.
        params.screenBrightness = Math.max(0.02f, value);
        activity.getWindow().setAttributes(params);
        showRail(brightnessRail, R.drawable.ic_brightness, params.screenBrightness);
    }

    private float currentVolume() {
        AudioManager audio = audio();
        if (audio == null) return 0f;
        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return max <= 0 ? 0f : audio.getStreamVolume(AudioManager.STREAM_MUSIC) / (float) max;
    }

    private void applyVolume(float value) {
        AudioManager audio = audio();
        if (audio == null) return;

        int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int target = Math.round(max * value);
        // No system UI of our own on top of ours: the flag suppresses the platform's volume
        // panel, which would otherwise appear over the rail saying the same thing.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
        showRail(volumeRail, R.drawable.ic_volume, max <= 0 ? 0f : target / (float) max);
    }

    @Nullable
    private AudioManager audio() {
        return (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
    }

    /** Fills the column by weight, which is what makes it grow upwards without a rotation. */
    private void showRail(View rail, int icon, float level) {
        rail.setVisibility(View.VISIBLE);
        ((ImageView) rail.findViewById(R.id.railIcon)).setImageResource(icon);

        View fill = rail.findViewById(R.id.railFill);
        View gap = rail.findViewById(R.id.railEmpty);
        ((LinearLayout.LayoutParams) fill.getLayoutParams()).weight = Math.max(0.001f, level);
        ((LinearLayout.LayoutParams) gap.getLayoutParams()).weight = Math.max(0.001f, 1f - level);
        fill.requestLayout();
        gap.requestLayout();
    }

    // ------------------------------------------------------------------ insets

    /**
     * Keeps the chrome clear of the status bar and the gesture area.
     *
     * <p>The video itself is deliberately not inset — it fills the window, black bars and all,
     * because a video letterboxed by the system bars as well as by its own aspect ratio is a
     * video in a box in a box.
     *
     * <p>Measured from the padding the layout was inflated with, never from the padding the view
     * currently has. This used to add the inset to whatever was already there, and insets are
     * delivered again on every rotation — so a turn into landscape, where there is a side inset,
     * and back again left the bottom bar carrying that inset twice over. What it looked like was
     * the seek bar ending well short of the duration beside it, on one side only.
     */
    public void applyInsets(int top, int bottom, int left, int right) {
        topBar.setPadding(baseTopBarLeft + left, top, baseTopBarRight + right, 0);
        bottomBar.setPadding(baseBottomBarLeft + left, 0,
                baseBottomBarRight + right, baseBottomBarBottom + bottom);
    }
}
