package com.ms.webview.push;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.ms.webview.MainActivity;
import com.ms.webview.R;

/**
 * Notifications about videos worth watching, and the tap that opens one.
 *
 * <p>Its own channel rather than the downloads one. A download notification is about something the
 * viewer already asked for; this is the app speaking up unprompted, and the two deserve to be
 * silenced separately — which a channel is exactly what allows.
 */
public class PushNotifier {

    public static final String CHANNEL_PUSH = "video_updates";

    private final Context context;
    private final NotificationManagerCompat manager;

    public PushNotifier(Context context) {
        this.context = context.getApplicationContext();
        this.manager = NotificationManagerCompat.from(this.context);
        createChannel();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        nm.createNotificationChannel(new NotificationChannel(
                CHANNEL_PUSH,
                context.getString(R.string.channel_push),
                NotificationManager.IMPORTANCE_DEFAULT));
    }

    /**
     * Shows one message.
     *
     * <p>Keyed on the link rather than on a running number, so the same video sent twice replaces
     * its own notification instead of arriving beside it. The pending intent is keyed the same
     * way, which matters more than it looks: reusing one request code across different links
     * would have {@code FLAG_UPDATE_CURRENT} quietly rewrite the older notification's link to the
     * newer one, and the first would open the wrong video.
     */
    public void show(@Nullable String title, @Nullable String body, String link) {
        int id = idFor(link);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_PUSH)
                .setSmallIcon(R.drawable.ic_video)
                .setContentTitle(TextUtils.isEmpty(title)
                        ? context.getString(R.string.push_default_title) : title)
                .setContentText(TextUtils.isEmpty(body) ? link : body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open(link, id));

        if (!TextUtils.isEmpty(body)) {
            b.setStyle(new NotificationCompat.BigTextStyle().bigText(body));
        }

        try {
            manager.notify(id, b.build());
        } catch (SecurityException e) {
            // POST_NOTIFICATIONS not granted on API 33+. Nothing else to do about it.
        }
    }

    /**
     * The tap: the app, carrying the link.
     *
     * <p>CLEAR_TOP and SINGLE_TOP for the same reason the download notification uses them — the
     * app is a single task, so this brings the one that exists forward and delivers the link to
     * it rather than building a second copy.
     */
    private PendingIntent open(String link, int id) {
        Intent i = new Intent(context, MainActivity.class)
                .putExtra(PushLink.EXTRA_PUSH_LINK, link)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, id, i, flags());
    }

    /** Positive and stable for a given link, and clear of the download notifier's own range. */
    private static int idFor(String link) {
        return 2000 + Math.abs(link.hashCode() % 100000);
    }

    private static int flags() {
        int f = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f |= PendingIntent.FLAG_IMMUTABLE;
        return f;
    }
}
