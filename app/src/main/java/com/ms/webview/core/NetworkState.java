package com.ms.webview.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

/**
 * Whether the connection we are on costs money.
 *
 * <p>"Unmetered" rather than "is Wi-Fi", because the promise the Wi-Fi-only setting makes is about
 * the bill, not the radio: a metered hotspot is a phone bill wearing a Wi-Fi hat, and a tethered
 * laptop connection the owner has marked unmetered should be allowed.
 *
 * <p>Read here as well as in the download service because screen 16 has to tell a held download
 * apart from a merely queued one, and the service does not say which is which — a held row is
 * simply put back to QUEUED. This is the other half of that inference and it must agree with the
 * service's own answer, which is why it is a shared reading rather than a second implementation.
 */
public final class NetworkState {

    private NetworkState() {
    }

    public static boolean unmetered(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        // No manager at all is not grounds for holding somebody's download hostage.
        if (cm == null) return true;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            // No capabilities means no network at all. Reported as metered so a download waits
            // rather than starting into nothing and failing.
            if (caps == null) return false;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
        }

        // API 24 and 25 have no per-network capabilities. isActiveNetworkMetered is the whole of
        // what the platform offers there, and it answers the question we are actually asking.
        NetworkInfo info = cm.getActiveNetworkInfo();
        if (info == null || !info.isConnected()) return false;
        return !cm.isActiveNetworkMetered();
    }
}
