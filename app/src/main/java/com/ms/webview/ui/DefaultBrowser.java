package com.ms.webview.ui;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.annotation.Nullable;

/**
 * Whether this app is the phone's browser, and how to ask to become it.
 *
 * <p>Asking is the whole difficulty. There is no API that makes an app the default browser — that
 * would be a setting an app could change about the phone without being told to, which is exactly
 * what it must not be. All any app can do is take the viewer to the place where they can say yes,
 * and which place that is depends on the version of Android.
 */
public final class DefaultBrowser {

    /** Any address will do; only which app claims it is being asked. */
    private static final Uri PROBE = Uri.parse("http://example.com");

    private DefaultBrowser() {
    }

    /**
     * Whether this app already handles web links by default.
     *
     * <p>From Android 10 there is a direct answer — the browser role is either held or it is not.
     * Before that the question has to be asked sideways, by resolving a web address and seeing who
     * comes back. Watch what that returns when nothing is set: the system's own chooser, under the
     * package name "android", which is not us and so is correctly not a yes.
     */
    public static boolean isDefault(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = context.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roles.isRoleHeld(RoleManager.ROLE_BROWSER);
            }
        }

        Intent probe = new Intent(Intent.ACTION_VIEW, PROBE)
                .addCategory(Intent.CATEGORY_BROWSABLE);
        ResolveInfo chosen = context.getPackageManager()
                .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY);
        return chosen != null
                && context.getPackageName().equals(chosen.activityInfo.packageName);
    }

    /**
     * Where to send the viewer to say yes, or null when there is nowhere to send them.
     *
     * <p>Two answers, and the first is much the better one. From Android 10 the browser role can
     * be requested outright: the system puts up its own dialog, the viewer taps once, and it is
     * done. Before that the best available is the settings screen listing default apps, which
     * lands them in the right place but leaves them to find the browser row themselves.
     *
     * <p>Null when neither is possible — an Android build with no default-apps screen, or one
     * where the browser role does not exist. There is then nothing to offer and nothing to ask.
     */
    @Nullable
    public static Intent requestIntent(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = context.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_BROWSER)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_BROWSER);
            }
        }

        Intent settings = new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS);
        return settings.resolveActivity(context.getPackageManager()) == null ? null : settings;
    }

    /** Whether asking is possible at all, so nothing is offered that cannot be carried out. */
    public static boolean canAsk(Context context) {
        return requestIntent(context) != null;
    }
}
