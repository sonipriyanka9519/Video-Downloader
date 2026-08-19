package com.ms.webview.ui.growth;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.ms.webview.R;

/**
 * The rate prompt — screen 18, panel B.
 *
 * <p><b>When it appears.</b> After the first successful download, and then after every third one
 * until the viewer actually answers. The first download is the moment the app has demonstrably
 * worked; after that, asking again immediately would be nagging, so three downloads have to pass
 * before the question comes back.
 *
 * <p><b>What counts as answering.</b> Rating or sending feedback, and nothing else. "Not now",
 * back, and a tap on the scrim all mean "not now" — they push the next ask three downloads out
 * rather than ending the conversation. Somebody who rates or writes in is never asked again.
 *
 * <p><b>Only successful downloads count</b>, and <b>never on cold open</b>. A failure is not a
 * reason to ask how somebody is enjoying the app, and asking the instant they open it is asking
 * before they have done anything.
 *
 * <p><b>Where the answer goes.</b> Four or five stars opens the store listing; three or fewer
 * opens an email instead. Nobody is sent to write a public review of an app they have just said is
 * not working for them, and the person who would have written that review gets a way to tell us
 * why instead.
 *
 * <p>No rating is submitted from in here — Play collects that on its own page. The stars choose a
 * destination rather than record a score, which is also why the button names the destination.
 */
public final class RatePrompt {

    private static final String PREFS = "rate_prompt";

    /** Successful downloads, ever. Never reset — it is the clock the schedule runs on. */
    private static final String KEY_COMPLETED = "completed";

    /** The count at which to ask next. One, until the first "not now" pushes it out. */
    private static final String KEY_NEXT_AT = "next_at";

    /** Set only when the viewer rated or wrote in. The end of the conversation. */
    private static final String KEY_DONE = "done";

    /** How many more downloads a "not now" buys. */
    private static final int INTERVAL = 3;

    /** Four or more goes to the store; three or fewer goes to an email. */
    private static final int STORE_THRESHOLD = 4;

    private static final int[] STAR_IDS = {
            R.id.rateStar1, R.id.rateStar2, R.id.rateStar3, R.id.rateStar4, R.id.rateStar5
    };

    /**
     * Whether this process has already shown the downloads list once.
     *
     * <p>This is the "never on cold open" rule, and it is deliberately in memory rather than in
     * prefs: the question is not "has the app ever been opened" but "did the viewer arrive here
     * just now", and that resets with the process.
     */
    private static boolean listSeenThisRun;

    /**
     * The sheet currently on screen, if any.
     *
     * <p>Without this, three downloads finishing together opened three sheets on top of each other
     * — each completion rebuilds the list, each rebuild asked, and none of them had been answered
     * yet. The schedule is written the moment the sheet is raised for the same reason.
     */
    private static BottomSheetDialog showing;

    private RatePrompt() {
    }

    /**
     * Counted when a download finishes successfully.
     *
     * <p>Called from the notifier because that is the one place every completion passes through
     * exactly once — the downloads list sees completions too, but it sees them again on every
     * rebuild, and a counter that double-counts would ask early and often.
     */
    public static void noteCompleted(Context context) {
        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_DONE, false)) return;
        prefs.edit().putInt(KEY_COMPLETED, prefs.getInt(KEY_COMPLETED, 0) + 1).apply();
    }

    /**
     * Raises the sheet if this is the moment for it.
     *
     * @return true when the sheet is now on screen, so a caller can hold back anything else
     */
    public static boolean maybeAsk(Context context) {
        boolean firstSight = !listSeenThisRun;
        listSeenThisRun = true;

        // Not on the first look at the list in this run — that is either a cold open or a return
        // from the background, and neither is somebody reacting to a download.
        if (firstSight) return false;
        if (showing != null && showing.isShowing()) return false;

        SharedPreferences prefs = prefs(context);
        if (prefs.getBoolean(KEY_DONE, false)) return false;

        int completed = prefs.getInt(KEY_COMPLETED, 0);
        if (completed < prefs.getInt(KEY_NEXT_AT, 1)) return false;

        show(context, completed);
        return true;
    }

    /**
     * The same sheet, asked for by hand from About.
     *
     * <p>Shown whatever the schedule says, because somebody who tapped "Rate this app" is not
     * being interrupted — they went looking. It still ends the conversation on a real answer.
     */
    public static void showNow(Context context) {
        if (showing != null && showing.isShowing()) return;
        show(context, prefs(context).getInt(KEY_COMPLETED, 0));
    }

    private static void show(Context context, int completed) {
        View content = LayoutInflater.from(context).inflate(R.layout.sheet_rate, null, false);

        BottomSheetDialog sheet = new BottomSheetDialog(context, R.style.ThemeOverlay_Ds_BottomSheet);
        sheet.setContentView(content);
        showing = sheet;

        // Written now, not on dismiss. A sheet that is on screen has been asked, and the next ask
        // is three downloads away whatever happens to this one — including the app being killed
        // while it is still up.
        defer(context, completed);

        MaterialButton primary = content.findViewById(R.id.btnRateNow);
        // Held in a one-element array because the listeners below close over it, and a local
        // cannot be reassigned from inside a lambda.
        final int[] stars = {0};

        for (int i = 0; i < STAR_IDS.length; i++) {
            final int rating = i + 1;
            ImageView star = content.findViewById(STAR_IDS[i]);
            star.setContentDescription(context.getString(R.string.rate_star_cd, rating));
            star.setOnClickListener(v -> {
                stars[0] = rating;
                paint(context, content, rating);
                // The label follows the choice, so the button always names where it goes.
                primary.setEnabled(true);
                primary.setText(rating >= STORE_THRESHOLD
                        ? R.string.rate_now : R.string.about_feedback);
            });
        }

        primary.setOnClickListener(v -> {
            if (stars[0] == 0) return;
            // A rating given is the end of the conversation either way — somebody who told us it
            // is a three and wrote in has answered as fully as somebody who left five stars.
            done(context);
            if (stars[0] >= STORE_THRESHOLD) openStore(context); else sendFeedback(context);
            sheet.dismiss();
        });

        // The two ways of saying no. Neither ends the conversation: deferring already happened
        // when the sheet went up, and both of these are exactly what it was written for.
        content.findViewById(R.id.btnRateLater).setOnClickListener(v -> sheet.dismiss());
        content.findViewById(R.id.btnRateFeedback).setOnClickListener(v -> {
            // Writing in without picking a star is still an answer, and the most useful kind.
            done(context);
            sendFeedback(context);
            sheet.dismiss();
        });

        sheet.setOnDismissListener(d -> showing = null);
        sheet.show();
    }

    /** Fills every star up to the one tapped, and empties the rest. */
    private static void paint(Context context, View content, int rating) {
        for (int i = 0; i < STAR_IDS.length; i++) {
            boolean filled = i < rating;
            ImageView star = content.findViewById(STAR_IDS[i]);
            star.setImageResource(filled ? R.drawable.ic_star_filled : R.drawable.ic_star);
            // Set on every pass, not only when filling: a star that has just been un-chosen has to
            // lose the accent as well as the shape.
            ImageViewCompat.setImageTintList(star, ColorStateList.valueOf(
                    ContextCompat.getColor(context,
                            filled ? R.color.ds_accent : R.color.ds_ink_faint)));
        }
    }

    /** Pushes the next ask out by {@link #INTERVAL} downloads from where the count stands now. */
    private static void defer(Context context, int completed) {
        prefs(context).edit().putInt(KEY_NEXT_AT, completed + INTERVAL).apply();
    }

    private static void done(Context context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).apply();
    }

    // ------------------------------------------------------------------ the two answers

    /**
     * The store listing — the market: scheme first, the web address as the fallback.
     *
     * <p>The same pair About uses. A device with no store app still has a browser, and a crash on
     * this button would be a poor way to end a request for a good review.
     */
    private static void openStore(Context context) {
        String id = context.getPackageName();
        if (open(context, "market://details?id=" + id)) return;
        if (open(context, "https://play.google.com/store/apps/details?id=" + id)) return;
        Toast.makeText(context, R.string.store_unavailable, Toast.LENGTH_SHORT).show();
    }

    private static void sendFeedback(Context context) {
        Intent mail = new Intent(Intent.ACTION_SENDTO)
                .setData(Uri.parse("mailto:" + context.getString(R.string.feedback_email)))
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name));
        try {
            context.startActivity(mail);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.about_no_mail_app, Toast.LENGTH_LONG).show();
        }
    }

    private static boolean open(Context context, String url) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
