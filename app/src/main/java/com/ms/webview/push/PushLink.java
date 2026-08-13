package com.ms.webview.push;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.URLUtil;

import androidx.annotation.Nullable;

import java.util.Map;

/**
 * The address inside a push message, wherever it arrives from.
 *
 * <p>One place for the key names because there are three routes in and they must agree. A message
 * this app displays itself puts the link in a pending intent; a message Firebase displays for us
 * puts the whole data payload into the extras of whatever the tap launches; and the launcher
 * screen hands those extras on. Any of the three can be the one that runs, and all of them are
 * looking for the same thing.
 */
public final class PushLink {

    /**
     * What the sender calls it. Two spellings, because a payload is written by hand and both are
     * the obvious name — rejecting one would fail silently and look like a broken notification.
     */
    private static final String[] KEYS = {"link", "url"};

    /** Where this app puts it when it builds the notification itself. */
    public static final String EXTRA_PUSH_LINK = "push_link";

    private PushLink() {
    }

    /** The link in a message's data payload, or null when it carries none. */
    @Nullable
    public static String from(@Nullable Map<String, String> data) {
        if (data == null) return null;
        for (String key : KEYS) {
            String value = accept(data.get(key));
            if (value != null) return value;
        }
        return null;
    }

    /**
     * The link in an intent's extras.
     *
     * <p>Both our own extra and the sender's own keys, since a notification Firebase displayed
     * copies the data payload into the launch intent verbatim and knows nothing of ours.
     */
    @Nullable
    public static String from(@Nullable Bundle extras) {
        if (extras == null) return null;

        String own = accept(extras.getString(EXTRA_PUSH_LINK));
        if (own != null) return own;

        for (String key : KEYS) {
            String value = accept(extras.getString(key));
            if (value != null) return value;
        }
        return null;
    }

    /**
     * Takes the link out of an intent, once it has been acted on.
     *
     * <p>An intent outlives the moment it arrived in — the one a notification launched the app
     * with remains the activity's intent for as long as the activity exists, and is handed back
     * whenever it is rebuilt. Left in place, the pushed video would open again in another new tab
     * each time that happened.
     */
    public static void consume(@Nullable Intent intent) {
        if (intent == null || intent.getExtras() == null) return;
        intent.removeExtra(EXTRA_PUSH_LINK);
        for (String key : KEYS) intent.removeExtra(key);
    }

    /**
     * A link only where it is really one.
     *
     * <p>Checked here rather than trusted, because a push comes from off the device. Whatever the
     * sender meant by it, anything that is not a web address is not something to open.
     */
    @Nullable
    private static String accept(@Nullable String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (TextUtils.isEmpty(trimmed) || !URLUtil.isNetworkUrl(trimmed)) return null;
        return trimmed;
    }
}
