package com.ms.webview.core;

import android.text.TextUtils;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

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
        return message != null && message.toLowerCase(java.util.Locale.US).contains("space");
    }
}
