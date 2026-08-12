package com.ms.webview.ui.onboard;

import android.content.Context;
import android.content.SharedPreferences;

/** Whether the introduction has been seen. One flag, so it is worth exactly one class. */
public final class Onboarding {

    private static final String PREFS = "onboarding";
    private static final String KEY_DONE = "done";

    private Onboarding() {
    }

    public static boolean isDone(Context context) {
        return prefs(context).getBoolean(KEY_DONE, false);
    }

    /**
     * Written synchronously: the activity finishes immediately afterwards, and an apply() that
     * had not landed before the process was killed would show the introduction a second time.
     */
    public static void markDone(Context context) {
        prefs(context).edit().putBoolean(KEY_DONE, true).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
