package com.ms.webview.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ms.webview.MainActivity;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;

public class DownloadNotifier {

    public static final String CHANNEL_PROGRESS = "downloads_progress";
    public static final String CHANNEL_DONE = "downloads_done";
    public static final int SUMMARY_ID = 1;

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

        NotificationChannel progress = new NotificationChannel(
                CHANNEL_PROGRESS,
                context.getString(R.string.channel_progress),
                NotificationManager.IMPORTANCE_LOW);
        progress.setShowBadge(false);
        nm.createNotificationChannel(progress);

        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE,
                context.getString(R.string.channel_done),
                NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(done);
    }

    /** The notification the foreground service itself runs under. */
    public Notification summary(int active, String detail) {
        return new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(context.getResources()
                        .getQuantityString(R.plurals.downloading_n, active, active))
                .setContentText(detail)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openApp())
                .build();
    }

    /** Refreshes the service's own notification in place. */
    public void updateSummary(int active, String detail) {
        notify(SUMMARY_ID, summary(active, detail));
    }

    public void showProgress(DownloadEntity d, long bytesPerSecond) {
        boolean indeterminate = d.totalBytes <= 0;
        String text = Formats.bytes(d.downloadedBytes)
                + (d.totalBytes > 0 ? " / " + Formats.bytes(d.totalBytes) : "")
                + (bytesPerSecond > 0 ? "  ·  " + Formats.speed(bytesPerSecond) : "");

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(d.title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(d.status == DownloadStatus.RUNNING)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, d.percent(), indeterminate)
                .setContentIntent(openApp());

        if (d.status == DownloadStatus.RUNNING) {
            b.addAction(0, context.getString(R.string.pause),
                    serviceAction(DownloadService.ACTION_PAUSE, d.id));
        } else if (d.status == DownloadStatus.PAUSED) {
            b.addAction(0, context.getString(R.string.resume),
                    serviceAction(DownloadService.ACTION_RESUME, d.id));
        }
        b.addAction(0, context.getString(R.string.cancel),
                serviceAction(DownloadService.ACTION_CANCEL, d.id));

        notify(idFor(d.id), b.build());
    }

    public void showFinished(DownloadEntity d) {
        boolean ok = d.status == DownloadStatus.COMPLETED;
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_download_done)
                .setContentTitle(d.title)
                .setContentText(context.getString(
                        ok ? R.string.download_complete : R.string.download_failed))
                .setAutoCancel(true)
                .setContentIntent(openApp());
        if (!ok && d.error != null) {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(d.error));
        }
        notify(idFor(d.id), b.build());
    }

    public void clear(long downloadId) {
        manager.cancel(idFor(downloadId));
    }

    public void clearAll() {
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

    private PendingIntent openApp() {
        // A download notification is about a download, so it opens on that tab rather than
        // wherever the browser happened to be left.
        Intent i = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, i, flags());
    }

    private PendingIntent serviceAction(String action, long id) {
        Intent i = new Intent(context, DownloadService.class)
                .setAction(action)
                .putExtra(DownloadService.EXTRA_ID, id);
        return PendingIntent.getService(context, (action + id).hashCode(), i, flags());
    }

    private static int flags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }
}
