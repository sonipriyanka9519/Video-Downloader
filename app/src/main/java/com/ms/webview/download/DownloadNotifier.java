package com.ms.webview.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ms.webview.MainActivity;
import com.ms.webview.R;
import com.ms.webview.core.Errors;
import com.ms.webview.core.Formats;
import com.ms.webview.core.NetworkState;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.ui.PlayerActivity;
import com.ms.webview.ui.growth.RatePrompt;
import com.ms.webview.ui.notify.NotificationActions;
import com.ms.webview.ui.settings.SettingsPrefs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app's surface outside the app — screen 16, panels A and B.
 *
 * <p>Three channels, and the split between them is the whole design. <b>Progress</b> is silent and
 * ongoing: it exists to be glanced at and controlled, never to interrupt. <b>Complete</b> is the
 * only one allowed to make a sound, because it is the only one reporting something that finished.
 * <b>Reminders</b> are low priority and off until the viewer asks for them — see
 * {@link com.ms.webview.ui.notify.UnwatchedReminder}.
 *
 * <p>Every failure carries its remedy as an action rather than sending somebody into the app to
 * find out what went wrong. "Reopen page" for an expired link, "Use mobile data" for a download
 * the Wi-Fi-only setting is holding — the mapping lives in {@link Errors.Remedy} so the message and
 * the button can never disagree.
 *
 * <p>Concurrent downloads collapse into one summary. Three downloads must never mean three ongoing
 * notifications, so the per-download cards are suppressed while more than one is running and the
 * summary carries Pause all instead.
 *
 * <p>Nothing here builds a custom layout. The shade is system chrome and the design says so
 * explicitly: we supply text, an icon, a thumbnail and actions, and let the platform draw it.
 */
public class DownloadNotifier {

    public static final String CHANNEL_PROGRESS = "downloads_progress";
    public static final String CHANNEL_DONE = "downloads_done";
    public static final String CHANNEL_REMINDERS = "downloads_reminders";
    public static final int SUMMARY_ID = 1;

    /** The reminder is one notification however many videos it is about. */
    public static final int REMINDER_ID = 2;

    /**
     * What each running download last reported, so the summary can add them up.
     *
     * <p>Kept here rather than read back out of the database: this is called from the download
     * loop several times a second, and a Room query on that path — or worse, on the main thread
     * where {@link #summary} is built — would cost more than the line of text is worth. Every
     * number in it arrives through {@link #showProgress} anyway.
     */
    private final Map<Long, long[]> live = new LinkedHashMap<>();

    private final Context context;
    private final NotificationManagerCompat manager;

    public DownloadNotifier(Context context) {
        this.context = context.getApplicationContext();
        this.manager = NotificationManagerCompat.from(this.context);
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Silent by construction, not merely by priority. A progress bar that pings every time it
        // moves is the reason people turn download notifications off altogether.
        NotificationChannel progress = new NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW);
        progress.setShowBadge(false);
        progress.setSound(null, null);
        progress.enableVibration(false);
        nm.createNotificationChannel(progress);

        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.channel_done),
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(done);

        // Its own channel so it can be silenced on its own. Somebody who wants to know when a
        // download finishes but never wants to be nudged about their library can have exactly
        // that, from the system's own settings, without us building a screen for it.
        NotificationChannel reminders = new NotificationChannel(
                CHANNEL_REMINDERS,
                context.getString(R.string.channel_reminders),
                NotificationManager.IMPORTANCE_LOW);
        reminders.setShowBadge(false);
        nm.createNotificationChannel(reminders);
    }

    // ------------------------------------------------------------------ the service's own

    /** The notification the foreground service itself runs under. */
    public Notification summary(int active, String detail) {
        return summaryBuilder(active, detail).build();
    }

    /** Refreshes the service's own notification in place. */
    public void updateSummary(int active, String detail) {
        notify(SUMMARY_ID, summary(active, detail));
    }

    /**
     * The collapsed line — screen 16, panel B, third card.
     *
     * <p>Aggregate figures where we have them. The service reports how many are running and which
     * one is current; the bytes come from {@link #live}, which is every number the download loop
     * has already handed us. When only one is running there is nothing to collapse and the summary
     * stays out of the way of that download's own card.
     */
    private NotificationCompat.Builder summaryBuilder(int active, String detail) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_download)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openDownloads());

        if (active > 1) {
            long[] total = totals();
            b.setContentTitle(context.getResources()
                    .getQuantityString(R.plurals.notif_downloads_in_progress, active, active));
            b.setContentText(progressLine(total[0], total[1], total[2], detail));
            if (total[1] > 0) {
                b.setProgress(100, (int) (total[0] * 100 / total[1]), false);
            }
            b.addAction(0, context.getString(R.string.notif_pause_all),
                    NotificationActions.pauseAll(context));
            b.addAction(0, context.getString(R.string.notif_open), openDownloads());
        } else {
            b.setContentTitle(context.getResources()
                    .getQuantityString(R.plurals.downloading_n, Math.max(1, active),
                            Math.max(1, active)));
            b.setContentText(detail);
        }
        return b;
    }

    /** Running totals across every live download: downloaded, total, bytes per second. */
    private long[] totals() {
        long done = 0;
        long size = 0;
        long speed = 0;
        for (long[] row : live.values()) {
            done += row[0];
            // One download of unknown size makes the whole sum a lie, so the total is only
            // reported when every part of it is known.
            if (size >= 0 && row[1] > 0) size += row[1]; else size = -1;
            speed += row[2];
        }
        return new long[]{done, size, speed};
    }

    // ------------------------------------------------------------------ per download

    public void showProgress(DownloadEntity d, long bytesPerSecond) {
        if (d.status == DownloadStatus.RUNNING) {
            live.put(d.id, new long[]{d.downloadedBytes, d.totalBytes, bytesPerSecond});
        } else {
            live.remove(d.id);
        }

        // Three downloads must never mean three ongoing notifications. While more than one is
        // running the summary speaks for all of them; the individual card comes back the moment
        // it is the only one left.
        if (live.size() > 1 && d.status == DownloadStatus.RUNNING) {
            manager.cancel(idFor(d.id));
            return;
        }

        notify(idFor(d.id), heldForWifi(d) ? waiting(d) : running(d, bytesPerSecond));
    }

    /**
     * Whether this row is being held by the Wi-Fi-only setting rather than by anything failing.
     *
     * <p>Derived rather than asked, because the service does not tell us — a held download is
     * simply put back to QUEUED. That plus the setting plus a metered connection is the only way
     * to arrive at this state, so reading it back is safe. Getting it wrong in the harmless
     * direction shows an ordinary queued card, which is what it used to show anyway.
     */
    private boolean heldForWifi(DownloadEntity d) {
        return d.status == DownloadStatus.QUEUED
                && d.downloadedBytes > 0
                && SettingsPrefs.wifiOnly(context)
                && !NetworkState.unmetered(context);
    }

    /** Screen 16, panel B, first card. */
    private Notification waiting(DownloadEntity d) {
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getString(R.string.notif_waiting_wifi))
                .setContentText(context.getString(R.string.notif_waiting_wifi_body, d.title))
                .setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(context.getString(R.string.notif_waiting_wifi_body, d.title)))
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openDownloads());

        // The bar stays, greyed, because the download has not lost its place — it is waiting, and
        // hiding the progress would suggest it had started over.
        if (d.totalBytes > 0) b.setProgress(100, d.percent(), false);

        b.addAction(0, context.getString(R.string.notif_use_mobile_data),
                NotificationActions.useMobileData(context, d.id));
        b.addAction(0, context.getString(R.string.cancel),
                serviceAction(DownloadService.ACTION_CANCEL, d.id));
        return b.build();
    }

    /** Screen 16, panel A, first card. */
    private Notification running(DownloadEntity d, long bytesPerSecond) {
        boolean indeterminate = d.totalBytes <= 0;

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(d.title)
                .setContentText(progressLine(d.downloadedBytes, d.totalBytes, bytesPerSecond, null))
                .setSilent(true)
                .setOnlyAlertOnce(true)
                // Not swipeable while it is running, per the design: dismissing the card would
                // leave a download going with nothing on screen to stop it.
                .setOngoing(d.status == DownloadStatus.RUNNING)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, d.percent(), indeterminate)
                .setContentIntent(openDownloads());

        if (d.status == DownloadStatus.RUNNING) {
            b.addAction(0, context.getString(R.string.pause),
                    serviceAction(DownloadService.ACTION_PAUSE, d.id));
        } else if (d.status == DownloadStatus.PAUSED) {
            b.addAction(0, context.getString(R.string.resume),
                    serviceAction(DownloadService.ACTION_RESUME, d.id));
        }
        b.addAction(0, context.getString(R.string.cancel),
                serviceAction(DownloadService.ACTION_CANCEL, d.id));
        return b.build();
    }

    /** "12.4 MB of 24.1 MB • 2.1 MB/s", with every part that is unknown left out. */
    private String progressLine(long done, long total, long bytesPerSecond, @Nullable String fallback) {
        if (total <= 0) {
            if (done <= 0) return fallback == null ? "" : fallback;
            return bytesPerSecond > 0
                    ? context.getString(R.string.notif_progress_speed,
                            Formats.bytes(done), Formats.speed(bytesPerSecond))
                    : Formats.bytes(done);
        }
        return bytesPerSecond > 0
                ? context.getString(R.string.notif_progress_of_speed,
                        Formats.bytes(done), Formats.bytes(total), Formats.speed(bytesPerSecond))
                : context.getString(R.string.notif_progress_of,
                        Formats.bytes(done), Formats.bytes(total));
    }

    // ------------------------------------------------------------------ finished

    /**
     * The one notification screen 10 can switch off — and only the successful half of it.
     *
     * <p>"Download complete" is a courtesy: the file is in the library either way, and somebody
     * who turned it off has said they will look when they want to. A failure is not that. It is
     * the only place the app can say a download did not arrive when nobody is on the screen, and
     * silencing it would let a failure pass for a success, which is the one thing this app must
     * never do.
     */
    public void showFinished(DownloadEntity d) {
        live.remove(d.id);
        boolean ok = d.status == DownloadStatus.COMPLETED;
        // Counted before the notification is decided, not after: whether the viewer wants to be
        // told about a finished download has nothing to do with whether it finished.
        if (ok) RatePrompt.noteCompleted(context);
        if (ok && !SettingsPrefs.notifyOnComplete(context)) return;
        notify(idFor(d.id), ok ? completed(d) : failed(d));
    }

    /**
     * Screen 16, panel A, second card.
     *
     * <p>The title states the event and the body names the file, rather than the other way round.
     * The shade groups by app, so a column of these all beginning with a different filename gives
     * no shape to scan; "Download complete" in the same place every time does.
     *
     * <p>The thumbnail is the large icon because it is the fastest way to recognise which of
     * several downloads this one was — faster than reading a truncated filename.
     */
    private Notification completed(DownloadEntity d) {
        String size = d.totalBytes > 0 ? Formats.bytes(d.totalBytes)
                : Formats.bytes(d.downloadedBytes);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_download_done)
                .setContentTitle(context.getString(R.string.download_complete))
                .setContentText(context.getString(R.string.notif_done_body, d.title, size))
                .setAutoCancel(true)
                .setContentIntent(watchIntent(d));

        Bitmap poster = thumbnail(d);
        if (poster != null) b.setLargeIcon(poster);

        b.addAction(0, context.getString(R.string.notif_watch_now), watchIntent(d));
        PendingIntent share = shareIntent(d);
        if (share != null) b.addAction(0, context.getString(R.string.share), share);
        return b.build();
    }

    /**
     * Screen 16, panel B, second card.
     *
     * <p>The remedy is the first action and it is never "Retry" for something retrying cannot fix
     * — see {@link Errors#remedyFor}. When there is genuinely nothing to press, nothing is offered
     * rather than a button that would fail again.
     */
    private Notification failed(DownloadEntity d) {
        String reason = TextUtils.isEmpty(d.error)
                ? context.getString(R.string.download_failed) : d.error;
        String body = context.getString(R.string.notif_failed_body, d.title, reason);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_download_done)
                .setContentTitle(context.getString(R.string.notif_failed_title))
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openDownloads());

        switch (Errors.remedyFor(d.error)) {
            case REOPEN_PAGE:
                if (!TextUtils.isEmpty(d.pageUrl)) {
                    b.addAction(0, context.getString(R.string.notif_reopen_page),
                            openPage(d.pageUrl));
                }
                break;
            case OPEN_SITE:
                if (!TextUtils.isEmpty(d.pageUrl)) {
                    b.addAction(0, context.getString(R.string.notif_open_site),
                            openPage(d.pageUrl));
                }
                break;
            case RETRY:
                b.addAction(0, context.getString(R.string.notif_retry),
                        serviceAction(DownloadService.ACTION_RESUME, d.id));
                break;
            case USE_MOBILE_DATA:
                b.addAction(0, context.getString(R.string.notif_use_mobile_data),
                        NotificationActions.useMobileData(context, d.id));
                break;
            case NONE:
            default:
                break;
        }

        b.addAction(0, context.getString(R.string.notif_dismiss),
                NotificationActions.dismiss(context, idFor(d.id)));
        return b.build();
    }

    // ------------------------------------------------------------------ the reminder

    /**
     * Screen 16, panel A, third card — and the only notification here nobody is waiting for.
     *
     * <p>Which is why it is low priority, capped at twice a week, and never posted unless the
     * viewer turned it on. See {@link com.ms.webview.ui.notify.UnwatchedReminder} for the counting
     * and the cap; this only draws it.
     */
    public void showUnwatchedReminder(int count) {
        Notification n = new NotificationCompat.Builder(context, CHANNEL_REMINDERS)
                .setSmallIcon(R.drawable.ic_download_done)
                .setContentTitle(context.getResources()
                        .getQuantityString(R.plurals.notif_unwatched_title, count, count))
                .setContentText(context.getString(R.string.notif_unwatched_body))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setContentIntent(openDownloads())
                .addAction(0, context.getString(R.string.notif_open_library), openDownloads())
                .build();
        notify(REMINDER_ID, n);
    }

    // ------------------------------------------------------------------ plumbing

    public void clear(long downloadId) {
        live.remove(downloadId);
        manager.cancel(idFor(downloadId));
    }

    public void clearAll() {
        live.clear();
        manager.cancel(SUMMARY_ID);
    }

    private void notify(int id, Notification n) {
        try {
            manager.notify(id, n);
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted on API 33+. Downloads still run.
        }
    }

    public static int idFor(long downloadId) {
        return (int) (1000 + downloadId);
    }

    /**
     * The completed download's own poster, decoded small.
     *
     * <p>A large icon is drawn at about 64dp, so decoding the full image to fill it would be
     * megabytes for a thumbnail. Failure is silent and simply means no image: a notification
     * without a picture is a smaller loss than one that never arrives.
     */
    @Nullable
    private Bitmap thumbnail(DownloadEntity d) {
        if (TextUtils.isEmpty(d.posterUrl)) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            decode(d.posterUrl, bounds);
            if (bounds.outWidth <= 0) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1, bounds.outWidth / 256);
            return decode(d.posterUrl, options);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    @Nullable
    private Bitmap decode(String url, BitmapFactory.Options options) throws Exception {
        // Local only. A poster that lives on the network is not worth a fetch on the path that
        // announces a finished download — the notification would arrive late or not at all.
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null || "file".equals(scheme)) {
            return BitmapFactory.decodeFile(uri.getPath(), options);
        }
        if ("content".equals(scheme)) {
            try (java.io.InputStream in = context.getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(in, null, options);
            }
        }
        return null;
    }

    private PendingIntent openDownloads() {
        // A download notification is about a download, so it opens on that tab rather than
        // wherever the browser happened to be left.
        Intent i = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, i, flags());
    }

    /** Straight into the player, which is what "Watch now" promises. */
    private PendingIntent watchIntent(DownloadEntity d) {
        if (TextUtils.isEmpty(d.outputUri)) return openDownloads();
        Intent i = PlayerActivity.intent(context, d.outputUri, d.title)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(context, (int) (5000 + d.id), i, flags());
    }

    @Nullable
    private PendingIntent shareIntent(DownloadEntity d) {
        if (TextUtils.isEmpty(d.outputUri)) return null;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(TextUtils.isEmpty(d.mime) ? "video/*" : d.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(d.outputUri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, context.getString(R.string.share))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, (int) (7000 + d.id), chooser, flags());
    }

    private PendingIntent openPage(String url) {
        Intent i = new Intent(context, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, url.hashCode(), i, flags());
    }

    private PendingIntent serviceAction(String action, long id) {
        Intent i = new Intent(context, DownloadService.class)
                .setAction(action)
                .putExtra(DownloadService.EXTRA_ID, id);
        return PendingIntent.getService(context, (action + id).hashCode(), i, flags());
    }

    static int flags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }
}
