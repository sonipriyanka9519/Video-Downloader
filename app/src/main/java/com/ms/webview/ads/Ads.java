package com.ms.webview.ads;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.google.android.gms.ads.admanager.AdManagerAdRequest;
import com.google.android.ump.ConsentInformation;
import com.google.android.ump.ConsentRequestParameters;
import com.google.android.ump.UserMessagingPlatform;

import com.ms.webview.BuildConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The gate every ad in this app passes through.
 *
 * <p><b>Consent first, always.</b> Nothing may be requested until the UMP form has been gathered
 * and answered, and {@link #ready()} is false until then — so a call site cannot accidentally get
 * ahead of it. In the EEA and the UK this is the law rather than a preference, and Play enforces it.
 *
 * <p>The SDK itself is initialised once, off the main thread, and only after consent allows it.
 * {@code MobileAds.initialize} does disk and network work; called from {@code onCreate} it is a
 * measurable part of how long the app takes to open.
 *
 * <p>Everything here fails silent and open: no consent, no network, an unconfigured unit id — the
 * app carries on without ads. An ad is never worth a screen that does not work.
 */
public final class Ads {

    private static final String TAG = "Ads";

    private static final AtomicBoolean started = new AtomicBoolean();
    private static volatile boolean initialised;

    /**
     * Work that asked for an ad before there was one to give.
     *
     * <p>This is what made ads appear on some devices and not others. Consent and SDK start take
     * as long as they take — a fast phone finishes before the first screen is built, a slow one or
     * a slow network does not — and a banner that asked too early simply did nothing and never
     * asked again. Now it waits here and runs the moment the answer arrives.
     */
    private static final List<Runnable> pending = new ArrayList<>();
    @Nullable
    private static ConsentInformation consent;

    private Ads() {
    }

    /**
     * Whether an ad may be requested right now.
     *
     * <p>Checked by every format before it loads. False while consent is still being gathered, when
     * consent was refused, and in a release build whose live ids are still placeholders.
     */
    public static boolean ready() {
        return initialised && AdIds.configured();
    }

    /**
     * Runs now if ads are ready, or as soon as they are.
     *
     * <p>Never runs at all when consent is refused, which is the point: the caller does not have
     * to know whether it was too early or told no.
     */
    public static void whenReady(Runnable work) {
        if (ready()) {
            work.run();
            return;
        }
        synchronized (pending) {
            pending.add(work);
        }
    }

    private static void drainPending(Context context) {
        List<Runnable> waiting;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            waiting = new ArrayList<>(pending);
            pending.clear();
        }
        // On the main thread: every one of these touches views.
        new Handler(Looper.getMainLooper()).post(() -> {
            for (Runnable work : waiting) work.run();
        });
    }

    /**
     * Gathers consent, then starts the SDK.
     *
     * <p>Called from the first activity, because the UMP form needs one to show itself. Safe to
     * call again — the work happens once per process.
     *
     * @param onSettled run when consent has resolved either way, so the caller can carry on
     */
    public static void start(Activity activity, @Nullable Runnable onSettled) {
        if (initialised) {
            if (onSettled != null) onSettled.run();
            return;
        }
        if (!started.compareAndSet(false, true)) return;

        ConsentRequestParameters.Builder params = new ConsentRequestParameters.Builder()
                .setTagForUnderAgeOfConsent(false);

        // Deliberately not forcing EEA geography.
        //
        // setDebugGeography is only honoured on a device registered with addTestDeviceHashedId,
        // and silently ignored everywhere else — so forcing it made the consent flow, and
        // therefore whether any ad appeared at all, differ from one test phone to the next.
        //
        // To test the form, take the hashed id logcat prints on the first run and add it with
        // ConsentDebugSettings.Builder.addTestDeviceHashedId, then set the geography here.

        ConsentInformation information = UserMessagingPlatform.getConsentInformation(activity);
        consent = information;

        information.requestConsentInfoUpdate(activity, params.build(),
                () -> UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity, error -> {
                    // A form that fails to load is not a refusal. canRequestAds decides.
                    if (error != null) {
                        Log.w(TAG, "Consent form: " + error.getMessage());
                    }
                    initialise(activity, information, onSettled);
                }),
                error -> {
                    // No consent information means no permission to assume any, so nothing is
                    // requested. The app is otherwise unaffected.
                    Log.w(TAG, "Consent update failed: " + error.getMessage());
                    if (onSettled != null) onSettled.run();
                });
    }

    private static void initialise(Context context, ConsentInformation information,
                                   @Nullable Runnable onSettled) {
        if (!information.canRequestAds()) {
            Log.i(TAG, "Consent withheld; no ads will be requested");
            if (onSettled != null) onSettled.run();
            return;
        }

        Context app = context.getApplicationContext();
        // Off the main thread: initialise reads from disk and talks to the network, and on the
        // opening screen that time is time the viewer spends looking at a splash.
        new Thread(() -> {
            try {
                MobileAds.initialize(app, status -> {
                });
            } catch (Throwable t) {
                Log.w(TAG, "MobileAds failed to start", t);
                return;
            }
            initialised = true;
            drainPending(app);
            if (onSettled != null) new Handler(Looper.getMainLooper()).post(onSettled);
        }, "ads-init").start();
    }

    /**
     * Whether a form is available for the viewer to change their answer later.
     *
     * <p>Consent has to be revocable, which means a row in Settings — see {@link #showPrivacyForm}.
     */
    public static boolean canChangeConsent() {
        return consent != null && consent.getPrivacyOptionsRequirementStatus()
                == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED;
    }

    public static void showPrivacyForm(Activity activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity, error -> {
            if (error != null) Log.w(TAG, "Privacy form: " + error.getMessage());
        });
    }

    static AdRequest request() {
        return new AdRequest.Builder().build();
    }

    /**
     * The right kind of request for the unit being asked for.
     *
     * <p>A "/network/unit" path is an Ad Manager unit and an "ca-app-pub-" string is an AdMob one,
     * and they do not take the same request object. Sending a plain AdRequest to an Ad Manager path
     * fills inconsistently rather than failing outright, which is the worst way to be wrong.
     */
    static AdManagerAdRequest adManagerRequest() {
        return new AdManagerAdRequest.Builder().build();
    }

    static AdRequest requestFor(String unitId) {
        return AdIds.isAdManager(unitId)
                ? new AdManagerAdRequest.Builder().build()
                : request();
    }
}
