package com.ms.webview.ads;

import android.content.Context;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdSize;
import com.google.android.gms.ads.AdView;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.admanager.AdManagerAdView;

/**
 * The fixed-size banner, for the two screens that are not lists.
 *
 * <p>Everywhere the app shows a list it shows a native card, because a list already has a row
 * language for one to borrow. The lock screen and the tab sheet have none: their content is a
 * keypad and a grid. A native card there would read as a row the app had invented, so these get a
 * plain bought rectangle instead, which is the more honest of the two.
 *
 * <p><b>The slot stays gone until an ad arrives</b>, so a failed request leaves no hole and nothing
 * shifts under a finger already on its way somewhere. Both placements sit directly above a control
 * the viewer reaches for, which is exactly the case that rule exists for.
 *
 * <p>Like every other format here it must be destroyed with the view that held it — a banner keeps
 * a running request and a web view of its own.
 */
public final class Banners {

    private static final String TAG = "Banners";

    private Banners() {
    }

    /** Fills a container with one banner, once consent allows it. */
    public static void load(Context context, @Nullable ViewGroup container, String unitId) {
        if (container == null) return;
        Ads.whenReady(() -> attach(context, container, unitId));
    }

    private static void attach(Context context, ViewGroup container, String unitId) {
        // Between asking and being answered the screen may have gone, or already been filled.
        if (container.getChildCount() > 0) return;

        // Two view types, chosen by the shape of the unit id — the same split the interstitial
        // needs. An Ad Manager path is not served through AdView, and an AdMob id is not served
        // through AdManagerAdView; sending either to the wrong one fails quietly.
        // Visible before the request, not after it.
        //
        // Both slots start gone in XML, so the banner was being added to a parent that is never
        // measured or laid out - a view of zero size at the moment loadAd was called. The comment
        // here used to claim the opposite. A banner asked for from an unlaid-out view is a
        // documented way to get "Internal error" back with nothing else to go on.
        //
        // The child carries the hiding instead: INVISIBLE is still measured and laid out, so the
        // SDK sees a real 320x50 view, and nothing is drawn until there is something to draw. It
        // also means the 50dp is reserved from the start, so neither the keypad above the lock
        // banner nor the button below the tab banner moves when an ad arrives.
        container.setVisibility(View.VISIBLE);

        if (AdIds.isAdManager(unitId)) {
            AdManagerAdView view = new AdManagerAdView(context);
            view.setAdUnitId(unitId);
            view.setAdSizes(AdSize.BANNER);
            view.setAdListener(listenerFor(container, view, unitId));
            view.setVisibility(View.INVISIBLE);
            container.addView(view);
            view.loadAd(Ads.adManagerRequest());
        } else {
            AdView view = new AdView(context);
            view.setAdUnitId(unitId);
            view.setAdSize(AdSize.BANNER);
            view.setAdListener(listenerFor(container, view, unitId));
            view.setVisibility(View.INVISIBLE);
            container.addView(view);
            view.loadAd(Ads.request());
        }
    }

    private static AdListener listenerFor(ViewGroup container, View banner, String unitId) {
        return new AdListener() {
            @Override
            public void onAdLoaded() {
                banner.setVisibility(View.VISIBLE);
                container.setVisibility(View.VISIBLE);
            }

            @Override
            public void onAdFailedToLoad(@NonNull LoadAdError error) {
                // Everything the SDK will say, not just the message. "Internal error" is the
                // catch-all and carries no information on its own; the domain says which layer
                // refused, and the cause is usually where the real reason is.
                Log.w(TAG, "Banner failed (" + unitId + ")"
                        + " code=" + error.getCode()
                        + " domain=" + error.getDomain()
                        + " message=" + error.getMessage()
                        + " cause=" + error.getCause()
                        + " response=" + error.getResponseInfo());
                container.setVisibility(View.GONE);
            }
        };
    }


    /**
     * Releases the banner a container is holding.
     *
     * <p>Both view types are checked by name rather than through a shared supertype, because the
     * two do not share one that carries destroy() publicly across SDK versions.
     */
    public static void destroy(@Nullable ViewGroup container) {
        if (container == null) return;
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (child instanceof AdManagerAdView) ((AdManagerAdView) child).destroy();
            else if (child instanceof AdView) ((AdView) child).destroy();
        }
        container.removeAllViews();
        container.setVisibility(View.GONE);
    }
}
