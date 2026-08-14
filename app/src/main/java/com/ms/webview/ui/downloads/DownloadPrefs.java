package com.ms.webview.ui.downloads;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * How the downloads list was last left: how it is laid out, and what order it is in.
 *
 * <p>Kept because both are choices about how somebody wants to work, not about one visit. Being put
 * back into a list view every time you open the app is the kind of small insistence that makes a
 * setting feel like it did not take.
 */
public final class DownloadPrefs {

    private static final String PREFS = "downloads_view";
    private static final String KEY_MODE = "mode";
    private static final String KEY_SORT = "sort";

    private DownloadPrefs() {
    }

    public static DownloadAdapter.Mode mode(Context context) {
        String saved = prefs(context).getString(KEY_MODE, DownloadAdapter.Mode.LIST.name());
        try {
            return DownloadAdapter.Mode.valueOf(saved);
        } catch (IllegalArgumentException e) {
            // Written by a version that had a mode this one does not.
            return DownloadAdapter.Mode.LIST;
        }
    }

    public static void setMode(Context context, DownloadAdapter.Mode mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name()).apply();
    }

    public static DownloadSort sort(Context context) {
        String saved = prefs(context).getString(KEY_SORT, DownloadSort.NEWEST.name());
        try {
            return DownloadSort.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return DownloadSort.NEWEST;
        }
    }

    public static void setSort(Context context, DownloadSort sort) {
        prefs(context).edit().putString(KEY_SORT, sort.name()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
