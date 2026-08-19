package com.ms.webview.ui.notify;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.app.NotificationManagerCompat;

import com.ms.webview.App;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.download.DownloadService;
import com.ms.webview.ui.settings.SettingsPrefs;

import java.util.List;

/**
 * The notification buttons that are not a single command to the download service — screen 16.
 *
 * <p>Two of them need a decision made before anything is sent. <b>Pause all</b> has to know which
 * downloads are running, and <b>Use mobile data</b> has to change the setting that is holding one
 * back before resuming it. Neither is something the service exposes as one action, and the service
 * is not ours to add to, so the thinking happens here and the service is told in the vocabulary it
 * already has.
 *
 * <p>Everything read from the database is read off the main thread. A broadcast receiver runs on
 * it, and {@link BroadcastReceiver#goAsync()} is what buys the time to leave it.
 */
public class NotificationActions extends BroadcastReceiver {

    private static final String ACTION_PAUSE_ALL = "com.ms.webview.notify.PAUSE_ALL";
    private static final String ACTION_MOBILE_DATA = "com.ms.webview.notify.MOBILE_DATA";
    private static final String ACTION_DISMISS = "com.ms.webview.notify.DISMISS";

    private static final String EXTRA_ID = "id";
    private static final String EXTRA_NOTIFICATION = "notification";

    // ------------------------------------------------------------------ factories

    public static PendingIntent pauseAll(Context context) {
        return broadcast(context, new Intent(context, NotificationActions.class)
                .setAction(ACTION_PAUSE_ALL), ACTION_PAUSE_ALL.hashCode());
    }

    public static PendingIntent useMobileData(Context context, long downloadId) {
        return broadcast(context, new Intent(context, NotificationActions.class)
                        .setAction(ACTION_MOBILE_DATA)
                        .putExtra(EXTRA_ID, downloadId),
                (int) (ACTION_MOBILE_DATA.hashCode() + downloadId));
    }

    public static PendingIntent dismiss(Context context, int notificationId) {
        return broadcast(context, new Intent(context, NotificationActions.class)
                        .setAction(ACTION_DISMISS)
                        .putExtra(EXTRA_NOTIFICATION, notificationId),
                ACTION_DISMISS.hashCode() + notificationId);
    }

    private static PendingIntent broadcast(Context context, Intent intent, int requestCode) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags);
    }

    // ------------------------------------------------------------------ handling

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) return;

        Context app = context.getApplicationContext();
        switch (action) {
            case ACTION_DISMISS:
                // Nothing to think about, so nothing is moved off this thread.
                NotificationManagerCompat.from(app)
                        .cancel(intent.getIntExtra(EXTRA_NOTIFICATION, -1));
                break;

            case ACTION_PAUSE_ALL:
                pauseEverything(app);
                break;

            case ACTION_MOBILE_DATA:
                allowMobileData(app, intent.getLongExtra(EXTRA_ID, -1));
                break;

            default:
                break;
        }
    }

    /**
     * Pauses every download that is actually running.
     *
     * <p>One message per download rather than one message for all of them, because pausing is a
     * per-download command in the service and inventing a bulk one would mean changing the engine
     * for a button. The list is read from the store, not from whatever the notification last drew,
     * so a download that started since the shade was pulled down is included.
     */
    private void pauseEverything(Context context) {
        final PendingResult pending = goAsync();
        App.get().repository().io().execute(() -> {
            try {
                List<DownloadEntity> all = App.get().repository().store().all();
                for (DownloadEntity d : all) {
                    if (d.status != DownloadStatus.RUNNING) continue;
                    DownloadService.control(context, DownloadService.ACTION_PAUSE, d.id);
                }
            } finally {
                pending.finish();
            }
        });
    }

    /**
     * Lets a held download off the Wi-Fi leash.
     *
     * <p>This turns the Wi-Fi-only setting off, which is a bigger act than the button's label
     * suggests and is the honest reading of it: there is no per-download override in the engine,
     * so "use mobile data for this one" is not a thing that can be promised. Somebody who presses
     * it has decided they would rather pay than wait, and the setting is where that decision
     * lives — it stays visible in Settings and can be put back with one tap.
     */
    private void allowMobileData(Context context, long id) {
        if (id < 0) return;
        SettingsPrefs.setWifiOnly(context, false);
        // Resume rather than trust the service's own network watcher: that watcher is waiting for
        // an unmetered connection to appear, and none is going to.
        DownloadService.control(context, DownloadService.ACTION_RESUME, id);
    }
}
