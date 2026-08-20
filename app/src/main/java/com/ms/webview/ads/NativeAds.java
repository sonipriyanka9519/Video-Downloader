package com.ms.webview.ads;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdListener;
import com.google.android.gms.ads.AdLoader;
import com.google.android.gms.ads.LoadAdError;
import com.google.android.gms.ads.nativead.MediaView;
import com.google.android.gms.ads.nativead.NativeAd;
import com.google.android.gms.ads.nativead.NativeAdView;
import com.ms.webview.R;

/**
 * The in-page ad, in the app's own card language — replacing the banners.
 *
 * <p>A banner is a rectangle the app has no say in, and dropped into a page built from ds_ tokens it
 * reads as a window cut into the screen. A native ad arrives as parts — headline, body, icon,
 * picture, call to action — and this assembles them into a card that belongs to the page it sits in.
 *
 * <p><b>Every displayed asset is registered with the NativeAdView.</b> That is not a formality: an
 * asset shown but not registered is an impression the SDK cannot report and a policy violation, and
 * the click handling is wired through the registration too.
 *
 * <p>Like the banners before it, the slot stays gone until an ad actually arrives, so a failed
 * request leaves no hole in the layout and nothing shifts under a finger already on its way
 * somewhere. And like them, it must be destroyed with the view that held it.
 */
public final class NativeAds {

    private static final String TAG = "NativeAds";

    /**
     * Google discards a native ad an hour after it loads; so does this, rather than showing one
     * that will report nothing.
     */
    private static final long MAX_AGE_MS = 60L * 60 * 1000;

    /**
     * One ad loaded and not yet shown.
     *
     * <p>This is why the slot used to sit empty for a second or two every time a screen opened.
     * The interstitial has been kept in hand since App start; the in-page ad had nothing of the
     * kind, so every appearance began with a network round trip that started only once the screen
     * was already built. Nothing was wrong — it was just always starting from zero.
     *
     * <p>Exactly one, and never shown twice. A native ad is displayed once and destroyed: handing
     * the same object to a second NativeAdView is both a second impression on one request and a
     * view that fights the one still holding it. So the spare is taken, not copied, and the next
     * is fetched behind it.
     *
     * <p>Main thread only. Every AdLoader callback lands there and every reader is a screen being
     * built, so there is nothing here to synchronise.
     */
    @Nullable
    private static NativeAd spare;
    private static long spareAt;
    private static boolean prefetching;

    /**
     * When the prefetch started — the same backstop the interstitial needs, for the same reason.
     *
     * <p>Set before the request and cleared only by a callback, this was a one-way latch: a request
     * that never returned meant no spare, forever, and no error to say so. The in-page ad recovers
     * better than the interstitial did, because a screen with no spare falls through to its own
     * request — but the spare is the whole point, and a wedged flag quietly gives up on it.
     */
    private static long prefetchSince;

    /** Longer than the SDK's own timeout, so this is a backstop and not a second deadline. */
    private static final long LOAD_TIMEOUT_MS = 75_000L;

    private static boolean prefetchInFlight() {
        if (!prefetching) return false;
        if (System.currentTimeMillis() - prefetchSince <= LOAD_TIMEOUT_MS) return true;
        Log.w(TAG, "Native prefetch never returned; asking again");
        prefetching = false;
        return false;
    }


    private NativeAds() {
    }

    /**
     * Fetches the first one, before any screen asks.
     *
     * <p>Called from App alongside the interstitial's preload, so the first slot to open has a
     * chance of being filled the moment it appears rather than a second after.
     */
    public static void preload(Context context) {
        Context app = context.getApplicationContext();
        Ads.whenReady(() -> prefetch(app));
    }

    /**
     * Loads the next one into the spare slot.
     *
     * <p>On the application context, not the screen that triggered it: this outlives whatever was
     * on screen when it started, and holding an Activity in a static field until a network request
     * comes back is a leak of the whole view tree.
     */
    /**
     * Loads the next one into the spare slot.
     *
     * <p>Takes the application context and nothing else. The spare outlives whatever screen
     * happened to trigger it - that is the entire point of it - so an Activity held here would be
     * a leak of that screen's whole view tree until the request came back, and a request started
     * on a screen that is then navigated away from must still finish.
     */
    private static void prefetch(Context context) {
        if (prefetchInFlight() || spare != null) return;
        prefetching = true;
        prefetchSince = System.currentTimeMillis();

        final Context app = context.getApplicationContext();
        final String unitId = AdIds.nativeAd();
        // Same reason as the interstitial: a throw here is indistinguishable from a request that
        // never answers, and both leave the latch set.
        try {
            new AdLoader.Builder(app, unitId)
                    .forNativeAd(ad -> {
                        Log.i(TAG, "Native spare ready");
                        spare = ad;
                        spareAt = System.currentTimeMillis();
                        prefetching = false;
                    })
                    .withAdListener(new AdListener() {
                        @Override
                        public void onAdFailedToLoad(@NonNull LoadAdError error) {
                            // No retry here. The next screen to open asks again, which costs nothing
                            // when there is no fill and avoids a request loop when there is none.
                            Log.w(TAG, "Native prefetch failed: " + error.getMessage());
                            prefetching = false;
                        }
                    })
                    .build()
                    .loadAd(Ads.requestFor(unitId));
        } catch (Throwable t) {
            Log.w(TAG, "Native prefetch threw", t);
            prefetching = false;
        }
    }

    /** The spare, if there is a fresh one. Taken, so it can never be shown in two places. */
    @Nullable
    private static NativeAd take() {
        NativeAd ready = spare;
        spare = null;
        if (ready == null) return null;
        if (System.currentTimeMillis() - spareAt > MAX_AGE_MS) {
            ready.destroy();
            return null;
        }
        return ready;
    }


    /**
     * Fills a container with one native ad.
     *
     * <p>Asked through {@link Ads#whenReady} rather than checked against it, so a screen built
     * before consent has resolved is not simply skipped — that was the bug that made ads appear on
     * fast devices and not slow ones.
     */
    public static void load(Activity activity, @Nullable ViewGroup container, String unitId) {
        if (container == null) return;
        Ads.whenReady(() -> attach(activity, container, unitId));
    }

    private static void attach(Activity activity, ViewGroup container, String unitId) {
        // Between asking and being answered the screen may have gone, or already been filled.
        if (activity.isFinishing() || activity.isDestroyed()) return;
        if (container.getChildCount() > 0) return;

        // The one already in hand, if there is one. This is the whole point of the spare: the card
        // is on screen in the same frame the slot is built, with no request in between.
        NativeAd ready = take();
        if (ready != null) {
            Log.i(TAG, "Native from spare");
            place(activity, container, ready);
            // And the one after it, so the next screen is just as quick.
            prefetch(activity.getApplicationContext());
            return;
        }

        AdLoader loader = new AdLoader.Builder(activity, unitId)
                .forNativeAd(ad -> {
                    // The ad can arrive after the screen has gone. Destroyed rather than leaked:
                    // a NativeAd holds bitmaps and a click surface.
                    if (activity.isFinishing() || activity.isDestroyed()
                            || container.getChildCount() > 0) {
                        ad.destroy();
                        return;
                    }
                    Log.i(TAG, "Native loaded on demand");
                    place(activity, container, ad);
                    prefetch(activity.getApplicationContext());
                })
                .withAdListener(new AdListener() {
                    @Override
                    public void onAdFailedToLoad(@NonNull LoadAdError error) {
                        Log.w(TAG, "Native failed (" + unitId + "): " + error.getMessage());
                        container.setVisibility(View.GONE);
                    }
                })
                .build();

        loader.loadAd(Ads.requestFor(unitId));
    }

    /**
     * Puts the card on screen, on the next frame rather than this one.
     *
     * <p>Inflating the card is the most expensive thing this class does - a whole view tree, plus
     * the SDK's own asset registration - and it was happening inside whatever pass had just asked
     * for the ad. On the screens that ask while they are still being built that landed on top of
     * their own layout and dropped a run of frames.
     *
     * <p>The work is identical; only its timing changes. The container may die in the gap, so it is
     * re-checked rather than trusted, and the ad is destroyed rather than leaked if it does.
     */
    private static void place(Activity activity, ViewGroup container, NativeAd ad) {
        container.post(() -> {
            if (activity.isFinishing() || activity.isDestroyed()
                    || container.getChildCount() > 0) {
                ad.destroy();
                return;
            }
            container.addView(render(activity, container, ad));
            container.setVisibility(View.VISIBLE);
        });
    }


    /**
     * Builds the card and tells the SDK which view is which.
     *
     * <p>The order matters: every asset is set on its view, then the view is handed to the
     * NativeAdView, and only at the end is the ad itself attached. Attaching first would register
     * views that have not been populated yet.
     */
    private static NativeAdView render(Activity activity, ViewGroup parent, NativeAd ad) {
        NativeAdView view = (NativeAdView) LayoutInflater.from(activity)
                .inflate(R.layout.ad_native, parent, false);

        TextView headline = view.findViewById(R.id.adHeadline);
        headline.setText(ad.getHeadline());
        view.setHeadlineView(headline);

        TextView body = view.findViewById(R.id.adBody);
        // Hidden rather than left blank: not every ad carries every asset, and an empty line is a
        // gap the card has paid for.
        body.setVisibility(TextUtils.isEmpty(ad.getBody()) ? View.GONE : View.VISIBLE);
        body.setText(ad.getBody());
        view.setBodyView(body);

        ImageView icon = view.findViewById(R.id.adIcon);
        if (ad.getIcon() != null) {
            icon.setImageDrawable(ad.getIcon().getDrawable());
            icon.setVisibility(View.VISIBLE);
            view.setIconView(icon);
        } else {
            icon.setVisibility(View.GONE);
        }

        TextView cta = view.findViewById(R.id.adCta);
        if (TextUtils.isEmpty(ad.getCallToAction())) {
            cta.setVisibility(View.GONE);
        } else {
            cta.setVisibility(View.VISIBLE);
            cta.setText(ad.getCallToAction());
            view.setCallToActionView(cta);
        }

        MediaView media = view.findViewById(R.id.adMedia);
        if (ad.getMediaContent() != null) {
            media.setVisibility(View.VISIBLE);

            // Filled, not fitted. Set here rather than left to the default so it is a decision on
            // the record: the box has a fixed size, and fitting the creative into it left bands of
            // empty card above and below every wide image. Cropping keeps the row solid.
            //
            // The cost is real and worth naming: a creative whose edges carry the product loses
            // them. FIT_CENTER is the one-word change back if that ever matters more.
            media.setImageScaleType(ImageView.ScaleType.CENTER_CROP);

            // Rounded corners on the picture itself, not only on the box behind it.
            //
            // The SDK puts its own ImageView or video surface inside the MediaView and fills the
            // bounds, so a rounded background behind it is simply covered up. Clipping the
            // MediaView to its outline is what actually cuts the corners off what it contains -
            // and the outline comes from ds_bg_ad_media.
            media.setClipToOutline(true);
            media.setMediaContent(ad.getMediaContent());
            view.setMediaView(media);
        } else {
            media.setVisibility(View.GONE);
        }

        // Last, and only once everything above is set.
        view.setNativeAd(ad);

        // Kept so destroy() can reach it. setNativeAd does not give it back, and without a handle
        // the ad outlives the view: NativeAdView.destroy() releases the view tree only, and the
        // NativeAd behind it - bitmaps, a video controller, a click surface - was never released
        // at all. One per visit to a screen, which is the shape of "it worked the first time".
        view.setTag(R.id.tag_native_ad, ad);
        return view;
    }

    /**
     * Releases the ad a container is holding.
     *
     * <p>From onDestroyView, like the banners. A NativeAd that outlives its view tree holds the
     * whole tree, and these live on tabs the viewer moves between constantly.
     */
    public static void destroy(@Nullable ViewGroup container) {
        if (container == null) return;
        // Backwards, so the walk is unaffected by anything leaving the container beneath it.
        for (int i = container.getChildCount() - 1; i >= 0; i--) {
            View child = container.getChildAt(i);
            if (child instanceof NativeAdView) {
                NativeAdView adView = (NativeAdView) child;
                // The view first, then the ad it was showing - which is what the tag is for.
                adView.destroy();
                Object held = adView.getTag(R.id.tag_native_ad);
                if (held instanceof NativeAd) ((NativeAd) held).destroy();
                adView.setTag(R.id.tag_native_ad, null);
            }
        }
        container.removeAllViews();
        container.setVisibility(View.GONE);
    }
}
