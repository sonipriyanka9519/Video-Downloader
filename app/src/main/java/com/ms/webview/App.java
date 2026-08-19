package com.ms.webview;

import android.app.Application;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.firebase.messaging.FirebaseMessaging;
import com.ms.webview.data.DownloadRepository;
import com.ms.webview.ui.BrowserFragment;
import com.ms.webview.ui.notify.UnwatchedReminder;
import com.ms.webview.ui.settings.SettingsPrefs;
import com.ms.webview.detect.MediaRegistry;

import java.util.HashMap;
import java.util.Map;

/**
 * Process-wide singletons. The registry lives here (not in the Activity) so that detection
 * survives configuration changes, and so the download service can read capture headers.
 */
public class App extends Application {

    private static App instance;

    /**
     * One registry per browser tab, so what a tab has found stays with it.
     *
     * <p>A single shared registry could only ever describe one page, so moving between tabs meant
     * clearing it and finding everything again — and finding it again meant reloading the page,
     * because the payloads a site sends on load are not sent twice. Going back to a tab therefore
     * showed nothing until it was refreshed.
     *
     * <p>Keyed on the tab's id rather than its address: a tab keeps its identity as it navigates,
     * and two tabs can be on the same page without sharing what they have found.
     */
    private final Map<String, MediaRegistry> registries = new HashMap<>();

    /** Whose registry {@link #registry()} means — the tab in front. */
    @Nullable
    private String currentTabId;

    private DownloadRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        // Before anything is laid out. AppCompat's night mode is process-wide state, and an
        // activity that starts before it is set inflates in the wrong theme and then has to be
        // recreated out of it — which the viewer sees as the app flashing on launch.
        SettingsPrefs.applyStoredTheme(this);
        repository = new DownloadRepository(this);
        // Re-laid on every launch because the reminder's alarm is inexact and therefore does not
        // survive a reboot. Cheap, idempotent, and a no-op for the viewer who never turned it on.
        UnwatchedReminder.schedule(this);
        // Once per launch of the app, and nowhere else. This runs exactly once per process, which
        // is the definition of "when the app is opened" — onStart would have re-armed it on every
        // return from another app, and that is how a one-off question becomes a nag.
        BrowserFragment.askAboutDefaultBrowser();
        logPushToken();
    }

    /**
     * The device's push address, written to the log at every start.
     *
     * <p>Only useful while there is no server: sending a test message to this one phone from the
     * Firebase console needs its token, and {@code onNewToken} fires when the token is issued —
     * which for an app already installed has long since happened. Asking for it here means it can
     * always be read, rather than only on the run that happened to mint it.
     */
    private void logPushToken() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Log.i("PushService", "FCM token: " + task.getResult());
            } else {
                Log.w("PushService", "No FCM token yet", task.getException());
            }
        });
    }

    public static App get() {
        return instance;
    }

    /**
     * The registry of the tab in front — what the download button and the sheet are asking about.
     *
     * <p>Never null. A caller that arrives before any tab has been named gets a registry of its
     * own rather than a null check to forget, and that one is simply never shown.
     */
    public MediaRegistry registry() {
        return registryFor(currentTabId == null ? "" : currentTabId);
    }

    /** The registry belonging to one tab, created the first time that tab needs one. */
    public synchronized MediaRegistry registryFor(String tabId) {
        MediaRegistry registry = registries.get(tabId);
        if (registry == null) {
            registry = new MediaRegistry(this);
            registries.put(tabId, registry);
        }
        return registry;
    }

    /** Called by the browser when it brings a tab to the front. */
    public void setCurrentTab(@Nullable String tabId) {
        currentTabId = tabId;
    }

    /** Called when a tab is closed. What it found goes with it. */
    public synchronized void forgetTab(String tabId) {
        registries.remove(tabId);
    }

    public DownloadRepository repository() {
        return repository;
    }
}
