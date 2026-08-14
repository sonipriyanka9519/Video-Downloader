package com.ms.webview.ui.guide;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens a site somewhere other than here.
 *
 * <p>Deliberately never this app's own browser, because of what the button is for. The first
 * method in every guide has the viewer share a video from the site into this app, and a share
 * sheet only exists where the site is a real app or a real browser tab. Opening our own WebView
 * would put them exactly where the guide is telling them not to be — and this app registers itself
 * as a handler for web links, so it has to be ruled out by name rather than by hoping.
 */
public final class SiteLauncher {

    private SiteLauncher() {
    }

    /** The site's own app where it is installed, a browser where it is not. */
    public static void open(Context context, String url, String appPackage, String chooserTitle) {
        if (openInApp(context, url, appPackage)) return;
        openInBrowser(context, url, chooserTitle);
    }

    /**
     * The site's own app, by its package, and only if it is really there.
     *
     * <p>Two ways in, because the two answer different questions. A link intent aimed at the
     * package lands on the page itself where the app claims its own web addresses; a launch
     * intent only opens the app at whatever it was showing. The first is better and not always
     * available.
     */
    private static boolean openInApp(Context context, String url, String appPackage) {
        PackageManager packages = context.getPackageManager();

        Intent deepLink = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(appPackage);
        if (deepLink.resolveActivity(packages) != null) {
            return launch(context, deepLink);
        }

        Intent launcher = packages.getLaunchIntentForPackage(appPackage);
        return launcher != null && launch(context, launcher);
    }

    /**
     * A browser, preferring whichever one the phone already treats as the default.
     *
     * <p>Only offered as a choice when the default cannot be used — when it is us, or when the
     * system would have shown a chooser anyway. Anything else would put a list in front of
     * somebody who has already told their phone which browser they want.
     */
    private static void openInBrowser(Context context, String url, String chooserTitle) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager packages = context.getPackageManager();

        ResolveInfo preferred = packages.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (preferred != null && !context.getPackageName().equals(preferred.activityInfo.packageName)
                && !"android".equals(preferred.activityInfo.packageName)) {
            if (launch(context, view)) return;
        }

        // No usable default. Offer everything that can open a web address except ourselves.
        List<Intent> others = new ArrayList<>();
        for (ResolveInfo info : packages.queryIntentActivities(view, 0)) {
            if (context.getPackageName().equals(info.activityInfo.packageName)) continue;
            others.add(new Intent(view).setComponent(new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name)));
        }
        if (others.isEmpty()) return;

        if (others.size() == 1) {
            launch(context, others.get(0));
            return;
        }
        Intent chooser = Intent.createChooser(others.remove(0), chooserTitle);
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, others.toArray(new Parcelable[0]));
        launch(context, chooser);
    }

    private static boolean launch(Context context, Intent intent) {
        try {
            // A dialog's context is an activity's, but a chooser raised from one that is finishing
            // needs its own task rather than a stack it can no longer join.
            if (!(context instanceof android.app.Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            // Uninstalled between being listed and being launched. Not worth a crash.
            return false;
        }
    }
}
