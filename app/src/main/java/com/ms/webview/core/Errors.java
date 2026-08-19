package com.ms.webview.core;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Turns transfer failures into something a user can act on.
 *
 * <p>"Download failed" tells nobody anything. Most failures here fall into a few buckets with
 * genuinely different remedies: an expired signed URL needs the page reopening, a refused one
 * needs a login, and a timeout just needs retrying.
 */
public final class Errors {

    private Errors() {
    }

    /**
     * What the viewer can actually do about a failure - screen 16, panel B.
     *
     * <p>The message already says what went wrong. This says what to press, and it lives here
     * beside the message so the two can never drift apart: a notification offering "Retry" for an
     * expired link would be worse than offering nothing, because it looks like it should work.
     */
    public enum Remedy {
        /** The signed URL is stale. Only the page can mint a new one. */
        REOPEN_PAGE,
        /** The server refused us. Signing in on the site is the only way through. */
        OPEN_SITE,
        /** Nothing is wrong with the link - the network gave out. */
        RETRY,
        /** Held by the Wi-Fi-only setting rather than by any failure. */
        USE_MOBILE_DATA,
        /** Nothing the viewer can press will help. Say so by offering nothing. */
        NONE
    }

    /**
     * Reads a remedy out of the message the download failed with.
     *
     * <p>Matched on the message rather than on an exception type because by the time a failure
     * reaches a notification it has been through the database as a string - the throwable is long
     * gone. The phrases matched are the ones this class produces above, so the two stay in step;
     * anything unrecognised gets {@link Remedy#RETRY}, which is safe in the sense that it cannot
     * mislead - the worst it does is fail again.
     */
    public static Remedy remedyFor(@Nullable String message) {
        if (TextUtils.isEmpty(message)) return Remedy.RETRY;
        String m = message.toLowerCase(Locale.US);

        if (m.contains("expired")) return Remedy.REOPEN_PAGE;
        if (m.contains("access denied") || m.contains("sign in")) return Remedy.OPEN_SITE;
        // Storage is the one class of failure no button in a notification can fix. Screen 13 is
        // where space is freed, and sending somebody there from the shade to delete things is a
        // longer errand than the notification can honestly promise.
        if (m.contains("storage") || m.contains("space")) return Remedy.NONE;
        return Remedy.RETRY;
    }

    public static String forStatus(int code) {
        switch (code) {
            case 401:
            case 403:
                return "Access denied by the server — sign in on the page and try again";
            case 404:
            case 410:
                return "Link expired — reopen the page to get a fresh one";
            case 429:
                return "The server is rate limiting us — wait a moment and retry";
            default:
                if (code >= 500) return "The server had an error (" + code + ") — retry shortly";
                return "HTTP " + code;
        }
    }

    public static String friendly(Throwable error) {
        if (error == null) return "Download failed";

        if (error instanceof UnknownHostException) return "No connection";
        if (error instanceof SocketTimeoutException) return "Connection timed out — retry";
        if (error instanceof InterruptedIOException) return "Transfer interrupted — retry";
        if (error instanceof FileNotFoundException) return "Storage unavailable";
        if (error instanceof IOException && isDiskFull(error)) return "Not enough storage space";

        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    private static boolean isDiskFull(Throwable error) {
        String message = error.getMessage();
        return message != null && message.toLowerCase(Locale.US).contains("space");
    }
}
