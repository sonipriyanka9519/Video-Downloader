package com.ms.webview.push;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * Where pushed messages arrive.
 *
 * <p>Only messages carrying a link are shown. A push from this app is an offer to watch something,
 * so one with nothing to open is not a quieter version of the same thing — it is a notification
 * that does nothing when tapped, and dropping it is better than showing it.
 *
 * <p>Worth knowing about how Firebase delivers, because it decides whether this class runs at all:
 * a message with a {@code notification} block is displayed by the system itself while the app is
 * in the background, and this method is never called. Only a data-only message reaches here every
 * time. Both routes are handled — this one builds the notification, and for the other the launch
 * intent's extras are read on the way in — but a sender who wants the app in charge of what the
 * notification looks like should send data only.
 */
public class PushService extends FirebaseMessagingService {

    private static final String TAG = "PushService";

    /** Sent by the composer as the notification's own title and body, where one was used. */
    @Override
    public void onMessageReceived(@NonNull RemoteMessage message) {
        super.onMessageReceived(message);

        String link = PushLink.from(message.getData());
        if (link == null) {
            Log.i(TAG, "Push with no link in its data payload; nothing to open, so nothing shown");
            return;
        }

        RemoteMessage.Notification notification = message.getNotification();
        String title = notification == null ? null : notification.getTitle();
        String body = notification == null ? null : notification.getBody();

        // The data payload may carry its own wording, which is the only way a data-only message
        // can say anything at all.
        if (title == null) title = message.getData().get("title");
        if (body == null) body = message.getData().get("body");

        new PushNotifier(this).show(title, body, link);
    }

    /**
     * The device's address for this app, reissued whenever Firebase decides to.
     *
     * <p>Logged rather than sent anywhere, because there is nowhere to send it yet: pushing to
     * this device from the Firebase console needs the token, and this is where to read it from.
     * A server that pushes to many devices would register it here instead.
     */
    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.i(TAG, "FCM token: " + token);
    }
}
