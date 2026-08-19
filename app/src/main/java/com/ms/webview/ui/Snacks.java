package com.ms.webview.ui;

import android.animation.ObjectAnimator;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.ms.webview.R;

/**
 * Every transient message in the app, built the same way.
 *
 * <p>A helper rather than a theme attribute, and the reason is the half-migrated theme: a
 * snackbar takes its look from the context it is raised against, and the activities are still on
 * the MVP palette. {@code Widget.Ds.Snackbar} exists and describes exactly this, but nothing
 * resolves {@code snackbarStyle} to it yet — so until the last screen is migrated, applying the
 * design here is what makes a message look the same wherever it comes from.
 *
 * <p>Both themes come free: the ds_snackbar_* tokens each have a night counterpart, which is why
 * they are used rather than a literal ink.
 */
public final class Snacks {

    /** How long a message about something now happening elsewhere stays up. */
    public static final int NOTICE_MS = 5000;

    /**
     * The timer bar's resolution. Fine enough that it moves smoothly and coarse enough that the
     * animator is not setting a property a thousand times for five seconds of decoration.
     */
    private static final int TIMER_STEPS = 500;

    private Snacks() {
    }

    /**
     * A message in the app's own clothes: an ink slab at the card radius, inset from the screen
     * edges, with the design's two text colours.
     *
     * @param anchor pinned above this when given — whatever is at the foot of the screen, so a
     *               message never lands on the controls it is reporting on
     */
    @NonNull
    public static Snackbar make(@NonNull View root, @NonNull CharSequence text, int durationMs,
                                @Nullable View anchor) {
        Snackbar bar = Snackbar.make(root, text, durationMs);
        if (anchor != null && anchor.getVisibility() == View.VISIBLE) {
            bar.setAnchorView(anchor);
        }

        View view = bar.getView();
        // The tint goes first, and it is the whole reason this looked right in one theme only.
        //
        // Material's SnackbarBaseLayout does not simply take the background it is given: it wraps
        // whatever you set and tints it with its own backgroundTint, which resolves to the inverse
        // surface. In light that is ink and the slab looked correct by accident; in dark it is a
        // pale colour, so the design's ink slab came out washed white with light text on it.
        view.setBackgroundTintList(null);
        view.setBackground(ContextCompat.getDrawable(
                view.getContext(), R.drawable.ds_bg_snackbar));
        // Clipped to that background's rounded outline, so anything added over the slab — the
        // timer bar below — stops at the corner rather than running out past it.
        view.setClipToOutline(true);

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams) {
            int margin = view.getResources().getDimensionPixelSize(R.dimen.ds_space_3);
            ((ViewGroup.MarginLayoutParams) params).setMargins(margin, margin, margin, margin);
            view.setLayoutParams(params);
        }

        TextView label = view.findViewById(com.google.android.material.R.id.snackbar_text);
        if (label != null) {
            label.setTextColor(ContextCompat.getColor(
                    view.getContext(), R.color.ds_snackbar_ink));
            // Two lines. More than that and it has stopped being a passing remark.
            label.setMaxLines(2);
        }
        bar.setActionTextColor(ContextCompat.getColor(
                view.getContext(), R.color.ds_snackbar_action));
        return bar;
    }

    /**
     * Runs a bar along the foot of a message for as long as it is up.
     *
     * <p>It measures the message's own life, not a download's — the download has a row on the
     * library for that, and this has no idea how long a transfer will take. What it answers is
     * the smaller question somebody asks of a message about to disappear: how long have I got.
     * Filling rather than draining, because it is showing time spent.
     *
     * <p>Added to the snackbar's view rather than built into a custom layout, so the message,
     * the action and their spacing stay Material's and only the bar is ours.
     */
    public static void withTimer(@NonNull Snackbar bar, int durationMs) {
        View view = bar.getView();
        if (!(view instanceof ViewGroup)) return;

        LinearProgressIndicator progress = new LinearProgressIndicator(view.getContext());
        progress.setIndeterminate(false);
        progress.setMax(TIMER_STEPS);
        progress.setProgress(0);
        progress.setTrackCornerRadius(0);
        progress.setTrackThickness(view.getResources()
                .getDimensionPixelSize(R.dimen.ds_progress_thickness));
        progress.setIndicatorColor(ContextCompat.getColor(
                view.getContext(), R.color.ds_snackbar_action));
        // No track: an unfilled line across a dark slab reads as a second element rather than as
        // the empty part of this one.
        progress.setTrackColor(ContextCompat.getColor(
                view.getContext(), android.R.color.transparent));

        ((ViewGroup) view).addView(progress, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        ObjectAnimator timer = ObjectAnimator.ofInt(progress, "progress", 0, TIMER_STEPS);
        timer.setDuration(durationMs);
        timer.setInterpolator(new LinearInterpolator());

        // Stopped when the message goes, whether it timed out or was swiped away. An animator
        // left running against a detached view is work nobody will ever see.
        bar.addCallback(new Snackbar.Callback() {
            @Override
            public void onDismissed(Snackbar dismissed, int event) {
                timer.cancel();
            }
        });
        timer.start();
    }
}
