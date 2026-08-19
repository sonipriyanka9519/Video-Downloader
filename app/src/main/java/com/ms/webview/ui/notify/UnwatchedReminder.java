package com.ms.webview.ui.notify;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import com.ms.webview.App;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.download.DownloadNotifier;
import com.ms.webview.ui.downloads.WatchedStore;
import com.ms.webview.ui.settings.SettingsPrefs;

import java.util.List;

/**
 * The nudge about videos nobody has watched — screen 16, panel A, third card.
 *
 * <p><b>Off until asked for.</b> This is the only notification the app sends that nobody is waiting
 * for, so it is the only one that has to be granted rather than merely tolerated. Nothing here runs
 * until {@link SettingsPrefs#notifyUnwatched} is true, and turning it off cancels the alarm rather
 * than leaving it firing into a check that always declines.
 *
 * <p><b>At most twice a week</b>, which the design fixes as the cap. Enforced by remembering when
 * the last one was sent rather than by trusting the alarm to be punctual — an inexact alarm can
 * fire early, and the cap is a promise to the viewer, not a scheduling detail.
 *
 * <p><b>Three or more, or nothing.</b> One unwatched video is not a backlog; it is a video somebody
 * has not got to yet. Below the threshold the alarm fires, finds nothing worth saying and stays
 * quiet, which is the intended outcome rather than a failure.
 *
 * <p>Inexact by choice. An exact alarm needs a permission on API 31+ and would be asking for the
 * right to interrupt precisely, for a message whose whole character is that it can wait. The cost
 * is that a reboot forgets the schedule until the app is next opened, which {@link #schedule} fixes
 * on the next launch — cheaper than holding RECEIVE_BOOT_COMPLETED for a reminder.
 */
public class UnwatchedReminder extends BroadcastReceiver {

    private static final String ACTION_CHECK = "com.ms.webview.notify.UNWATCHED_CHECK";
    private static final String PREFS = "unwatched_reminder";
    private static final String KEY_LAST_SENT = "last_sent";

    /** Twice a week, expressed as the gap between two of them. */
    private static final long MIN_GAP_MS = 3L * 24 * 60 * 60 * 1000 + 12L * 60 * 60 * 1000;

    /** How often the alarm looks. Often enough to catch the gap, rare enough to cost nothing. */
    private static final long CHECK_INTERVAL_MS = AlarmManager.INTERVAL_DAY;

    /** Fewer than this is not a backlog worth mentioning. */
    private static final int THRESHOLD = 3;

    /**
     * Puts the schedule in place, or takes it away.
     *
     * <p>Safe to call on every launch and after every change to the setting — an alarm replaced by
     * an identical alarm is not two alarms.
     */
    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarms = app.getSystemService(AlarmManager.class);
        if (alarms == null) return;

        PendingIntent check = check(app);
        if (!SettingsPrefs.notifyUnwatched(app)) {
            alarms.cancel(check);
            return;
        }

        alarms.setInexactRepeating(
                AlarmManager.RTC,
                System.currentTimeMillis() + CHECK_INTERVAL_MS,
                CHECK_INTERVAL_MS,
                check);
    }

    private static PendingIntent check(Context context) {
        Intent i = new Intent(context, UnwatchedReminder.class).setAction(ACTION_CHECK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, ACTION_CHECK.hashCode(), i, flags);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION_CHECK.equals(intent.getAction())) return;

        Context app = context.getApplicationContext();
        // Checked again here rather than relying on the alarm having been cancelled: a pending
        // alarm can outlive the setting that created it.
        if (!SettingsPrefs.notifyUnwatched(app)) return;

        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long last = prefs.getLong(KEY_LAST_SENT, 0);
        if (last > 0 && System.currentTimeMillis() - last < MIN_GAP_MS) return;

        final PendingResult pending = goAsync();
        App.get().repository().io().execute(() -> {
            try {
                int waiting = countUnwatched(app);
                if (waiting < THRESHOLD) return;

                new DownloadNotifier(app).showUnwatchedReminder(waiting);
                prefs.edit().putLong(KEY_LAST_SENT, System.currentTimeMillis()).apply();
            } finally {
                pending.finish();
            }
        });
    }

    /**
     * How many finished videos have never been played.
     *
     * <p>Resume positions are the single source for this, the same field Continue Watching reads,
     * so the count in the notification and the dot in the library can never disagree.
     *
     * <p>Private items are skipped. They are not in MediaStore and so are not in this list at all,
     * but the check is written down anyway: the invariant is that a private video never reaches a
     * notification, and a future change to where this list comes from should fail loudly here
     * rather than quietly leak one.
     */
    private int countUnwatched(Context context) {
        List<DownloadEntity> all = App.get().repository().store().all();
        int count = 0;
        for (DownloadEntity d : all) {
            if (d.status != DownloadStatus.COMPLETED) continue;
            if (TextUtils.isEmpty(d.outputUri)) continue;
            if (!WatchedStore.isUnwatched(context, d.outputUri)) continue;
            count++;
        }
        return count;
    }
}
