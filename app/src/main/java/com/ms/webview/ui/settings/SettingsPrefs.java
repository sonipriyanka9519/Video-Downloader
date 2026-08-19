package com.ms.webview.ui.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * What the settings screen remembers — screen 10.
 *
 * <p>One store rather than a preference per feature, because the screen is the only writer and
 * several readers elsewhere want a single obvious place to look.
 *
 * <p>Every value here has a default that matches the design's own copy, so a fresh install reads
 * the same way the canvas does.
 */
public final class SettingsPrefs {

    private static final String PREFS = "settings";

    private static final String KEY_QUALITY = "default_quality";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    private static final String KEY_PARALLEL = "parallel";
    private static final String KEY_THEME = "theme";
    private static final String KEY_NOTIFY_DONE = "notify_done";
    private static final String KEY_NOTIFY_UNWATCHED = "notify_unwatched";
    private static final String KEY_REMINDER_OFFERED = "reminder_offered";

    /** How many at once, when the engine is asked to honour it. The design's own default. */
    public static final int DEFAULT_PARALLEL = 2;

    private SettingsPrefs() {
    }

    // ------------------------------------------------------------------ downloads

    public static DefaultQuality quality(Context context) {
        return DefaultQuality.of(prefs(context).getString(KEY_QUALITY, null));
    }

    public static void setQuality(Context context, DefaultQuality quality) {
        prefs(context).edit().putString(KEY_QUALITY, quality.name()).apply();
    }

    public static boolean wifiOnly(Context context) {
        return prefs(context).getBoolean(KEY_WIFI_ONLY, false);
    }

    public static void setWifiOnly(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_WIFI_ONLY, value).apply();
    }

    /**
     * Whether a download started right now would sit in the queue instead of running.
     *
     * <p>Asked before the queueing so the message can say which of the two happened. The engine
     * makes the same judgement for itself when the job reaches it — this is only for the wording,
     * and it deliberately does not try to influence what the engine decides.
     *
     * <p>"Not metered" rather than "is Wi-Fi": a hotspot is Wi-Fi and still somebody's data, and
     * an unmetered mobile connection is exactly what the setting is trying to allow.
     */
    public static boolean willWaitForWifi(Context context) {
        if (!wifiOnly(context)) return false;

        ConnectivityManager cm = (ConnectivityManager)
                context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        Network active = cm.getActiveNetwork();
        if (active == null) return true;   // Nothing at all is certainly not Wi-Fi.
        NetworkCapabilities caps = cm.getNetworkCapabilities(active);
        return caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }

    public static int parallel(Context context) {
        return prefs(context).getInt(KEY_PARALLEL, DEFAULT_PARALLEL);
    }

    public static void setParallel(Context context, int value) {
        prefs(context).edit().putInt(KEY_PARALLEL, value).apply();
    }

    // ------------------------------------------------------------------ appearance

    public static ThemeChoice theme(Context context) {
        return ThemeChoice.of(prefs(context).getString(KEY_THEME, null));
    }

    /**
     * Applies a theme and remembers it.
     *
     * <p>Applied through {@link AppCompatDelegate} rather than by recreating anything here: it
     * recreates every started activity itself, which is what makes the change reach the screen
     * underneath this one as well as this one.
     */
    public static void setTheme(Context context, ThemeChoice choice) {
        prefs(context).edit().putString(KEY_THEME, choice.name()).apply();
        AppCompatDelegate.setDefaultNightMode(choice.mode);
    }

    /** Re-applies the stored theme at startup, before the first screen is laid out. */
    public static void applyStoredTheme(Context context) {
        AppCompatDelegate.setDefaultNightMode(theme(context).mode);
    }

    // ------------------------------------------------------------------ notifications

    public static boolean notifyOnComplete(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY_DONE, true);
    }

    public static void setNotifyOnComplete(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_DONE, value).apply();
    }

    /**
     * Whether screen 16's reminder card has already been put to the viewer.
     *
     * <p>Separate from the setting itself, because "no" and "never asked" are different answers
     * and only one of them means ask again. Set by either button, so declining is an answer rather
     * than a deferral - Settings still carries the switch for anyone who changes their mind.
     */
    public static boolean reminderOfferMade(Context context) {
        return prefs(context).getBoolean(KEY_REMINDER_OFFERED, false);
    }

    public static void setReminderOfferMade(Context context) {
        prefs(context).edit().putBoolean(KEY_REMINDER_OFFERED, true).apply();
    }

    /**
     * Off until asked for, which is the whole character of this notification.
     *
     * <p>The default was true while nothing read it, and that was harmless right up until screen
     * 16 gave it a scheduler: a reminder nobody asked for would have started arriving on its own,
     * which is exactly what the design forbids and exactly the kind of notification that gets an
     * app's notifications turned off wholesale. It also made the opt-in card unreachable, since
     * the card only offers what is not already on.
     *
     * <p>Every other notification here reports something the viewer set in motion. This one does
     * not, so it is the one that has to be granted.
     */
    public static boolean notifyUnwatched(Context context) {
        return prefs(context).getBoolean(KEY_NOTIFY_UNWATCHED, false);
    }

    public static void setNotifyUnwatched(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_UNWATCHED, value).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
