package com.ms.webview.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.webkit.URLUtil;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * The link on the clipboard, and whether it is still worth offering.
 *
 * <p>Someone who copies a link elsewhere and opens this app almost always means to open that link
 * here, so it is worth asking. Asking twice about the same link is not: the second time it is not
 * an offer, it is the app arguing with an answer already given. So every link that has been put in
 * front of the viewer is remembered, whether they took it or not, and never offered again.
 *
 * <p>A short list rather than a single value, because the alternative gets it wrong in an ordinary
 * case: decline a link, open something else, come back, and one remembered value has already been
 * overwritten by the page you opened — so the declined link returns as though it were new.
 */
public final class ClipboardPrompt {

    private static final String PREFS = "clipboard_prompt";
    private static final String KEY_HANDLED = "handled";

    /**
     * How many links back the memory goes.
     *
     * <p>Enough to cover moving between a few pages after declining one, and no more. This exists
     * to stop repeat offers, not to keep a history — the history screen is that.
     */
    private static final int REMEMBERED = 8;

    /** Newlines cannot appear in a URL, so they are safe to join on. */
    private static final String SEPARATOR = "\n";

    private ClipboardPrompt() {
    }

    /**
     * The clipboard's link, if there is one and it has not been offered before.
     *
     * <p>Null in every other case, including the one that is not a failure at all: from Android 10
     * the clipboard may only be read while the app's window has focus, and outside that the system
     * hands back nothing rather than refusing. There is no way to tell an empty clipboard from an
     * unreadable one, and no reason to — both mean there is nothing to offer right now.
     */
    @Nullable
    public static String pending(Context context) {
        String text = clipboardText(context);
        if (TextUtils.isEmpty(text) || !URLUtil.isNetworkUrl(text)) return null;
        return handled(context).contains(text) ? null : text;
    }

    /**
     * Records a link as dealt with, so it is not offered again.
     *
     * <p>Called for both answers — opened and cancelled — because the question has been asked
     * either way. Also called for anything the browser loads, so a link opened from somewhere else
     * in the app is not then offered by a dialog a moment later.
     */
    public static void markHandled(Context context, @Nullable String url) {
        if (TextUtils.isEmpty(url)) return;

        List<String> links = handled(context);
        links.remove(url);
        links.add(0, url);
        while (links.size() > REMEMBERED) links.remove(links.size() - 1);

        prefs(context).edit()
                .putString(KEY_HANDLED, TextUtils.join(SEPARATOR, links))
                .apply();
    }

    private static List<String> handled(Context context) {
        List<String> links = new ArrayList<>();
        String stored = prefs(context).getString(KEY_HANDLED, "");
        if (TextUtils.isEmpty(stored)) return links;

        for (String link : stored.split(SEPARATOR)) {
            if (!TextUtils.isEmpty(link)) links.add(link);
        }
        return links;
    }

    @Nullable
    private static String clipboardText(Context context) {
        try {
            ClipboardManager clipboard = ContextCompat.getSystemService(
                    context, ClipboardManager.class);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;

            CharSequence text = clip.getItemAt(0).coerceToText(context);
            return text == null ? null : text.toString().trim();
        } catch (Exception e) {
            // A clipboard the system will not hand over is one with nothing to offer.
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
