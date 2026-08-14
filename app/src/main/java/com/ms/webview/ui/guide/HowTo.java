package com.ms.webview.ui.guide;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Whether the walkthrough has been read.
 *
 * <p>Read by the browser only. The downloads screen shows its own offer for good — there it is one
 * row at the foot of a list, and it costs nothing to leave. On the browser's home screen the
 * button sits directly under the shortcuts, in the space somebody is aiming at, and once the
 * walkthrough has been read it is a permanent obstacle in front of an answered question.
 */
public final class HowTo {

    private static final String PREFS = "how_to";
    private static final String KEY_SEEN = "seen";

    private HowTo() {
    }

    public static boolean isSeen(Context context) {
        return prefs(context).getBoolean(KEY_SEEN, false);
    }

    /**
     * Recorded on opening rather than on finishing.
     *
     * <p>Opening it is what the button was for. Someone who looks at the first step and closes it
     * has decided they do not need the rest, and putting the button back would be arguing with
     * that — the walkthrough stays in the browser's overflow, and on the downloads screen, for
     * anyone who wants it again.
     */
    public static void markSeen(Context context) {
        prefs(context).edit().putBoolean(KEY_SEEN, true).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
