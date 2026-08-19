package com.ms.webview.ui.downloads;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * How far through each download the viewer has got.
 *
 * <p>One field, two surfaces, as the invariant requires: the same recorded position decides
 * whether a video still counts as unwatched, how long the red bar under its thumbnail is, and
 * where the player starts when it is opened again. A separate "watched" flag beside it would be
 * a second thing to keep in step, and the two would disagree the first time one was written and
 * the other was not.
 *
 * <p>The position and the duration are kept together in one value. The fraction is what the
 * library draws, the milliseconds are what the player seeks to, and deriving one from the other
 * needs both — storing only the fraction meant a resume had to wait for the player to report a
 * duration before it knew where to go.
 *
 * <p>Keyed by the published uri, which is what survives a rename — a library row's id comes from
 * its MediaStore id, and the file's name is the one thing about it that changes.
 */
public final class WatchedStore {

    private static final String PREFS = "watched";

    /**
     * Past this, a video is finished rather than part-watched.
     *
     * <p>Most videos end in credits or a sign-off nobody sits through, so waiting for 100% would
     * leave a library full of things that are over but still listed as unwatched.
     */
    private static final float FINISHED = 0.95f;

    /**
     * Below this, opening it again starts from the beginning.
     *
     * <p>Dropping somebody four seconds in is worse than not resuming at all: it looks like a
     * glitch rather than a courtesy, and the part they missed is the part that says what they
     * are watching.
     */
    private static final long MIN_RESUME_MS = 5_000L;

    /** Counts edits, so a screen can tell whether anything it draws has actually changed. */
    private static final String KEY_REVISION = "revision";

    private WatchedStore() {
    }

    /**
     * How many times a watch position has been written, ever.
     *
     * <p>The downloads list used to rebuild itself on every visit on the chance that the player
     * had been somewhere in between. Rebuilding is not free - it rebinds every visible row, and a
     * rebound row redraws its watched bar from scratch - so the list now asks this first and does
     * nothing at all when the answer has not moved.
     *
     * <p>A counter rather than a timestamp: two writes in the same millisecond are still two
     * writes, and nothing here needs to know when they happened.
     */
    public static int revision(Context context) {
        return prefs(context).getInt(KEY_REVISION, 0);
    }

    private static void bump(Context context) {
        prefs(context).edit().putInt(KEY_REVISION, revision(context) + 1).apply();
    }

    /** How far through, from 0 to 1. Zero for anything never opened. */
    public static float progress(Context context, String outputUri) {
        long[] mark = mark(context, outputUri);
        if (mark == null || mark[1] <= 0) return 0f;
        return Math.max(0f, Math.min(1f, mark[0] / (float) mark[1]));
    }

    /**
     * True while there is still something left to watch.
     *
     * <p>Includes a video part-way through, deliberately: half-watched is not watched, and the
     * filter exists to find the things still waiting for you.
     */
    public static boolean isUnwatched(Context context, String outputUri) {
        return progress(context, outputUri) < FINISHED;
    }

    /**
     * Where the player should start, in milliseconds. Zero to start from the beginning.
     *
     * <p>Zero for a video already finished as well as one never opened: reopening something you
     * watched to the end means watching it again, not staring at its last frame.
     */
    public static long resumePosition(Context context, String outputUri) {
        long[] mark = mark(context, outputUri);
        if (mark == null || mark[1] <= 0) return 0L;
        if (mark[0] < MIN_RESUME_MS) return 0L;
        if (mark[0] / (float) mark[1] >= FINISHED) return 0L;
        return mark[0];
    }

    /** Recorded by the player as it leaves, so the library is right when it comes back. */
    public static void setProgress(Context context, String outputUri,
                                   long positionMs, long durationMs) {
        if (TextUtils.isEmpty(outputUri) || durationMs <= 0) return;

        long clamped = Math.max(0L, Math.min(positionMs, durationMs));
        // A tap that opened the file and closed it again is not progress, and a bar one pixel
        // wide under every thumbnail says nothing.
        if (clamped / (float) durationMs < 0.01f) return;
        prefs(context).edit().putString(outputUri, clamped + "/" + durationMs).apply();
        bump(context);
    }

    public static void forget(Context context, String outputUri) {
        if (TextUtils.isEmpty(outputUri)) return;
        prefs(context).edit().remove(outputUri).apply();
        bump(context);
    }

    /**
     * The stored {position, duration} pair, or null when there is none.
     *
     * <p>Reads defensively: this file has held a bare float in an earlier build, and a store
     * that throws on its own old data would take the library down with it.
     */
    private static long[] mark(Context context, String outputUri) {
        if (TextUtils.isEmpty(outputUri)) return null;

        String raw;
        try {
            raw = prefs(context).getString(outputUri, null);
        } catch (ClassCastException oldFormat) {
            // A float under this key is the previous shape. There is no duration to pair it
            // with, so it cannot be converted — dropping it costs one video's place.
            forget(context, outputUri);
            return null;
        }
        if (TextUtils.isEmpty(raw)) return null;

        int slash = raw.indexOf('/');
        if (slash <= 0) return null;
        try {
            return new long[]{
                    Long.parseLong(raw.substring(0, slash)),
                    Long.parseLong(raw.substring(slash + 1))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
