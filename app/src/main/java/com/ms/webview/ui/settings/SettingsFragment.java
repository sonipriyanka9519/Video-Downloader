package com.ms.webview.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.ms.webview.App;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.ui.DefaultBrowser;
import com.ms.webview.ui.DefaultBrowserSheet;
import com.ms.webview.ui.SystemBars;
import com.ms.webview.ui.downloads.PrivateStore;
import com.ms.webview.ui.guide.WalkthroughActivity;
import com.ms.webview.ui.home.SearchHistory;
import com.ms.webview.ui.lock.AppLock;
import com.ms.webview.ui.lock.PrivateAuth;
import com.ms.webview.ui.notify.UnwatchedReminder;
import com.ms.webview.ui.storage.StorageActivity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Screen 10 — Settings.
 *
 * <p>Six sections and about two screens of rows. That is the whole design: no search, no nested
 * pages, and every row's subtitle carrying either its current value or its consequence so nothing
 * here needs explaining somewhere else.
 *
 * <p>The rows are built from a spec rather than written into the layout. Sixteen near-identical
 * blocks of XML would be sixteen chances to get a padding wrong, and two of them are conditional —
 * the default-browser row disappears entirely once the app is default.
 *
 * <p><b>A fragment, hosted twice.</b> It is the bottom navigation's third tab and it is what
 * SettingsActivity shows, because the browser's overflow opens settings as a screen of its own. One
 * list either way: the alternative was the same sixteen rows written twice and drifting apart.
 */
public class SettingsFragment extends Fragment {

    private static final String TAG = "Settings";

    /**
     * Whether this copy shows a back arrow.
     *
     * <p>True in the activity, which is somewhere you arrived at and can leave; false in the tab,
     * which has nothing behind it to go back to.
     */
    private static final String ARG_SHOW_BACK = "show_back";

    /** How many downloads at once the parallel row offers. */
    private static final Integer[] PARALLEL_OPTIONS = {1, 2, 3, 4};

    private ViewGroup list;

    /** Moving the private folder's contents out is a real file copy — never on the main thread. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Registered in onCreate, because an activity result cannot be registered from a click. */
    private PrivateAuth privateAuth;

    /** For the activity, which owns a back arrow; the tab uses the no-argument constructor. */
    public static SettingsFragment withBack() {
        SettingsFragment fragment = new SettingsFragment();
        Bundle args = new Bundle();
        args.putBoolean(ARG_SHOW_BACK, true);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        privateAuth = new PrivateAuth(this, requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        list = view.findViewById(R.id.settingsList);

        boolean showBack = getArguments() != null && getArguments().getBoolean(ARG_SHOW_BACK);
        View back = view.findViewById(R.id.btnSettingsBack);
        back.setVisibility(showBack ? View.VISIBLE : View.GONE);
        back.setOnClickListener(v -> requireActivity().finish());

        // Only the copy that owns its window pads itself. In the tab the activity has already
        // handled the status bar, and padding again would push the title down twice.
        if (showBack) SystemBars.pad(view.findViewById(R.id.settingsRoot));

        build();
    }

    /**
     * Rebuilt on every return rather than refreshed.
     *
     * <p>Several rows lead into Android's own settings, and the answer they show — whether this is
     * the default browser, what the language is — can have changed while this was in the background.
     */
    @Override
    public void onResume() {
        super.onResume();
        build();
    }

    @Override
    public void onDestroyView() {
        // Dropped with the view it came from, so nothing rebuilt afterwards can write into a
        // layout that is no longer on screen.
        list = null;
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the list

    private void build() {
        // onResume can arrive with no view behind it — a tab scrolled far enough off the
        // pager has had onDestroyView called on it while the fragment itself lives on.
        if (list == null || !isAdded()) return;
        list.removeAllViews();

        section(R.string.settings_downloads);
        row(R.drawable.ic_quality, R.string.setting_default_quality,
                getString(SettingsPrefs.quality(requireContext()).label), true, this::chooseQuality);
        toggle(R.drawable.ic_wifi, R.string.setting_wifi_only,
                getString(R.string.setting_wifi_only_body), SettingsPrefs.wifiOnly(requireContext()),
                value -> SettingsPrefs.setWifiOnly(requireContext(), value));
        row(R.drawable.ic_layers, R.string.setting_parallel,
                getResources().getQuantityString(R.plurals.setting_parallel_value,
                        SettingsPrefs.parallel(requireContext()), SettingsPrefs.parallel(requireContext())),
                true, this::chooseParallel);
        row(R.drawable.ic_folder, R.string.setting_location,
                getString(R.string.setting_location_value), true, this::openStorageSettings);

        divider();
        section(R.string.settings_appearance);
        row(R.drawable.ic_contrast, R.string.setting_theme,
                getString(SettingsPrefs.theme(requireContext()).label), true, this::chooseTheme);
        row(R.drawable.ic_language, R.string.setting_language, currentLanguage(), true,
                this::openLanguageSettings);

        divider();
        section(R.string.settings_privacy);
        // What it locks is the private folder, not the app — so the subtitle says which, rather
        // than a bare "Off" that reads as though the whole app were unguarded.
        boolean locked = AppLock.isEnabled(requireContext());
        row(R.drawable.ic_lock, R.string.setting_app_lock,
                getString(locked ? R.string.lock_state_on_body : R.string.lock_state_off_body),
                true, locked ? this::askTurnOffLock : this::askTurnOnLock);
        row(R.drawable.ic_delete_history, R.string.setting_clear_history, null, false,
                this::confirmClearHistory);
        // Screen 12's one decision, once it has been made. The question itself is put inside the
        // first Clear all, where somebody is already thinking about how much history to keep; this
        // is where the answer lives afterwards and where it can be changed.
        toggle(R.drawable.ic_history, R.string.setting_history_retention,
                getString(R.string.setting_history_retention_body),
                SearchHistory.autoClearOld(requireContext()),
                value -> SearchHistory.setAutoClearOld(requireContext(), value));
        // Two rows rather than one "clear browsing data": they cost different things. Cache is
        // only speed, cookies are every logged-in session — including the ones the downloader
        // replays to fetch media from sites that only serve it to a signed-in viewer.
        //
        // Distinct icons, too: three rows in a row all carrying the same glyph reads as one
        // control drawn three times.
        row(R.drawable.ic_storage, R.string.setting_clear_cache,
                getString(R.string.setting_clear_cache_body), false, this::confirmClearCache);
        row(R.drawable.ic_globe, R.string.setting_clear_cookies,
                getString(R.string.setting_clear_cookies_body), false, this::confirmClearCookies);

        divider();
        section(R.string.settings_notifications);
        toggle(R.drawable.ic_check_circle, R.string.setting_notify_complete, null,
                SettingsPrefs.notifyOnComplete(requireContext()),
                value -> SettingsPrefs.setNotifyOnComplete(requireContext(), value));
        // Screen 16's reminder, and the only notification the app sends that nobody asked for -
        // which is why it is off until this is turned on. Switching it rebuilds the alarm rather
        // than only writing the pref, so turning it off stops the checks instead of leaving them
        // running into a test that always declines.
        toggle(R.drawable.ic_notifications, R.string.setting_notify_unwatched,
                getString(R.string.setting_notify_unwatched_body),
                SettingsPrefs.notifyUnwatched(requireContext()),
                value -> {
                    SettingsPrefs.setNotifyUnwatched(requireContext(), value);
                    UnwatchedReminder.schedule(requireContext());
                });

        divider();
        section(R.string.settings_general);
        // Gone once the app is default, and gone on a phone that has no screen for choosing one,
        // rather than sitting there doing nothing — the design is explicit that a row which
        // cannot change anything should not be on screen.
        if (!DefaultBrowser.isDefault(requireContext()) && DefaultBrowser.canAsk(requireContext())) {
            row(R.drawable.ic_open_in_browser, R.string.setting_default_browser,
                    getString(R.string.opens_android_settings), true, this::offerDefaultBrowser);
        }
        row(R.drawable.ic_help, R.string.how_to_download, null, true,
                () -> WalkthroughActivity.open(requireContext()));
        // Screen 13, not Android's app-details page. The app knows what it is using and can offer
        // to do something about it; the system screen can only report a number.
        row(R.drawable.ic_storage, R.string.setting_storage, storageSummary(), true,
                () -> StorageActivity.open(requireContext()));

        divider();
        section(R.string.settings_about);
        // One row rather than three. Share, rate and the version all moved to screen 17's About
        // page, which is also where the privacy policy has to be reachable from - and a policy
        // link is not something to bury between two loose rows at the end of a long list.
        row(R.drawable.ic_info, R.string.about, versionSummary(), true,
                () -> AboutActivity.open(requireContext()));
    }

    private void section(@StringRes int label) {
        TextView view = (TextView) LayoutInflater.from(requireContext())
                .inflate(R.layout.item_settings_section, list, false);
        view.setText(label);
        list.addView(view);
    }

    private void divider() {
        list.addView(LayoutInflater.from(requireContext())
                .inflate(R.layout.item_settings_divider, list, false));
    }

    /**
     * A row that opens something, or simply acts.
     *
     * @param chevron true when tapping leads somewhere; false when it does the thing
     * @param action  null for a row that only reports, like the version
     */
    private void row(@DrawableRes int icon, @StringRes int title, @Nullable CharSequence subtitle,
                     boolean chevron, @Nullable Runnable action) {
        View view = inflateRow(icon, title, subtitle);
        view.findViewById(R.id.settingChevron)
                .setVisibility(chevron ? View.VISIBLE : View.GONE);

        if (action == null) {
            // Nothing to press. Left un-clickable rather than given a no-op listener, so it does
            // not ripple under a finger and promise something.
            view.setClickable(false);
            view.setBackground(null);
        } else {
            view.setOnClickListener(v -> action.run());
        }
        list.addView(view);
    }

    private void toggle(@DrawableRes int icon, @StringRes int title,
                        @Nullable CharSequence subtitle, boolean checked,
                        Toggle onChanged) {
        View view = inflateRow(icon, title, subtitle);
        MaterialSwitch control = view.findViewById(R.id.settingSwitch);
        control.setVisibility(View.VISIBLE);
        control.setChecked(checked);

        // The row toggles, not the switch — the switch is not clickable, so there is one target
        // rather than a big one and a small one that do the same thing.
        view.setOnClickListener(v -> {
            boolean next = !control.isChecked();
            control.setChecked(next);
            onChanged.onChanged(next);
        });
        list.addView(view);
    }

    private View inflateRow(@DrawableRes int icon, @StringRes int title,
                            @Nullable CharSequence subtitle) {
        View view = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_settings_row, list, false);
        ((ImageView) view.findViewById(R.id.settingIcon)).setImageResource(icon);
        ((TextView) view.findViewById(R.id.settingTitle)).setText(title);

        TextView sub = view.findViewById(R.id.settingSubtitle);
        sub.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        sub.setText(subtitle);
        return view;
    }

    private interface Toggle {
        void onChanged(boolean value);
    }

    // ------------------------------------------------------------------ choices

    private void chooseQuality() {
        List<DefaultQuality> options = Arrays.asList(DefaultQuality.values());
        ChoiceSheet.show(requireContext(), getString(R.string.setting_default_quality),
                getString(R.string.setting_default_quality_body),
                options, labels(options), bodies(options),
                SettingsPrefs.quality(requireContext()), choice -> {
                    SettingsPrefs.setQuality(requireContext(), choice);
                    build();
                });
    }

    private void chooseTheme() {
        List<ThemeChoice> options = Arrays.asList(ThemeChoice.values());
        List<CharSequence> labels = new ArrayList<>();
        for (ThemeChoice option : options) labels.add(getString(option.label));

        ChoiceSheet.show(requireContext(), getString(R.string.setting_theme), options, labels,
                SettingsPrefs.theme(requireContext()), choice -> {
                    // Recreates every started activity itself, which is what carries the change
                    // to the screen underneath this one as well as to this one.
                    SettingsPrefs.setTheme(requireContext(), choice);
                });
    }

    private void chooseParallel() {
        List<Integer> options = Arrays.asList(PARALLEL_OPTIONS);
        List<CharSequence> labels = new ArrayList<>();
        for (Integer option : options) {
            labels.add(getResources().getQuantityString(
                    R.plurals.setting_parallel_value, option, option));
        }

        ChoiceSheet.show(requireContext(), getString(R.string.setting_parallel),
                getString(R.string.setting_parallel_body), options, labels,
                SettingsPrefs.parallel(requireContext()), choice -> {
                    SettingsPrefs.setParallel(requireContext(), choice);
                    build();
                });
    }

    private List<CharSequence> labels(List<DefaultQuality> options) {
        List<CharSequence> labels = new ArrayList<>();
        for (DefaultQuality option : options) labels.add(getString(option.label));
        return labels;
    }

    /** The consequence under each option — screen 17, panel A. */
    private List<CharSequence> bodies(List<DefaultQuality> options) {
        List<CharSequence> bodies = new ArrayList<>();
        for (DefaultQuality option : options) bodies.add(getString(option.body));
        return bodies;
    }

    // ------------------------------------------------------------------ actions

    /**
     * Removes visited pages and says so first.
     *
     * <p>The message names what is not affected as well as what is: on a downloader, "clear
     * history" is a phrase people hesitate over precisely because they are afraid it means their
     * files.
     */
    private void confirmClearHistory() {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.clear_history_title)
                .setMessage(R.string.clear_history_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    // Everything, both lists. This is the privacy control, and a typed search is
                    // the plainest record there is of what somebody went looking for.
                    SearchHistory.clear(requireContext());
                    SearchHistory.clearQueries(requireContext());
                    Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * Turning it on goes through the same intro and setup as every other route in.
     *
     * <p>Not straight to the keypad: somebody arriving from this row may never have heard of the
     * private folder, and a PIN screen with no explanation is a PIN protecting nothing they know
     * about. See {@link PrivateAuth}.
     */
    private void askTurnOnLock() {
        privateAuth.require(requireContext(), 0, this::build);
    }

    /**
     * Turning it off asks for the lock first — the whole point of a lock is that the person holding
     * the phone cannot simply switch it off.
     */
    private void askTurnOffLock() {
        privateAuth.require(requireContext(), R.string.lock_reason_disable, this::confirmTurnOffLock);
    }

    /**
     * Then it asks again, because turning it off is not undone by turning it back on.
     *
     * <p>The PIN and the recovery answer are forgotten, not merely ignored — they are hashes and
     * there is nothing to keep. And the folder cannot outlive the lock: whatever is still in it
     * comes back to the library, which the message says before rather than after.
     */
    private void confirmTurnOffLock() {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.lock_turn_off_title)
                .setMessage(R.string.lock_turn_off_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lock_turn_off, (dialog, which) -> turnOffLock())
                .show();
    }

    /**
     * Empties the private folder, then forgets the credential.
     *
     * <p>That order, and only that order. Videos in the folder live in this app's own storage and
     * nothing else can see them; disabling the lock first would leave files that are unprotected
     * and still invisible to the gallery — the worst of both. So they are published back to the
     * library first, and if any of them will not go, the lock stays on and says so. A viewer whose
     * files are still hidden is better served by a lock that refused to switch off than by one
     * that switched off and left them stranded.
     */
    private void turnOffLock() {
        List<PrivateStore.Item> items = PrivateStore.all(requireContext());
        if (items.isEmpty()) {
            AppLock.disable(requireContext());
            build();
            Toast.makeText(requireContext(), R.string.lock_turned_off, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(requireContext(), R.string.private_moving, Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            int failed = 0;
            for (PrivateStore.Item item : items) {
                if (PrivateStore.moveOut(requireContext(), item) == null) failed++;
            }
            final int stuck = failed;
            main.post(() -> {
                if (!isAdded()) return;

                // The library has files it does not know about yet.
                App.get().repository().refreshLibrary();
                if (stuck > 0) {
                    Toast.makeText(requireContext(), getString(R.string.private_move_failed_n,
                            stuck, items.size()), Toast.LENGTH_LONG).show();
                    build();
                    return;
                }
                AppLock.disable(requireContext());
                build();
                Toast.makeText(requireContext(), R.string.lock_turned_off, Toast.LENGTH_SHORT).show();
            });
        });
    }

    /**
     * Clears the WebView's cache.
     *
     * <p>Confirmed even though nothing is lost by it, because "clear" on a downloader is a word
     * people rightly hesitate over — the message is mostly there to say that the files are not
     * what this touches.
     */
    private void confirmClearCache() {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.setting_clear_cache)
                .setMessage(R.string.clear_cache_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    // A WebView is needed to clear the cache and none is on this screen. One made
                    // for the purpose clears the same store — it is per-app, not per-instance —
                    // and is destroyed immediately so it cannot outlive this activity.
                    WebView scratch = new WebView(requireContext());
                    scratch.clearCache(true);
                    scratch.destroy();
                    Toast.makeText(requireContext(), R.string.cache_cleared, Toast.LENGTH_SHORT).show();
                    // The storage line counts library files, not cache, but rebuilding is cheap
                    // and keeps every summary on the screen honest at the same moment.
                    build();
                })
                .show();
    }

    /**
     * Signs the browser out of everything.
     *
     * <p>Worth a firmer warning than the cache: several sites only serve media to a signed-in
     * viewer, and the downloader replays those same cookies out of band. Clearing them does not
     * break anything permanently, but the next download from such a site will need a login first,
     * and being told that afterwards would look like the app had broken.
     */
    private void confirmClearCookies() {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.setting_clear_cookies)
                .setMessage(R.string.clear_cookies_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    CookieManager cookies = CookieManager.getInstance();
                    cookies.removeAllCookies(null);
                    // Written through immediately: without this they are gone in memory only and
                    // come back with the process.
                    cookies.flush();
                    Toast.makeText(requireContext(), R.string.cookies_cleared, Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    /**
     * The offer, then the handoff — never the handoff on its own.
     *
     * <p>This row used to leave straight for Android's default-apps screen, which is a jump out of
     * the app with nothing said about what is being asked for. The same sheet the browser raises
     * on its own explains it first; {@link #askDefaultBrowser()} is what happens after a yes.
     */
    private void offerDefaultBrowser() {
        DefaultBrowserSheet.show(requireContext(), this::askDefaultBrowser, null);
    }

    private void askDefaultBrowser() {
        Intent request = DefaultBrowser.requestIntent(requireContext());
        if (request == null) {
            // Said out loud rather than returned in silence. The viewer pressed a button and the
            // row it came from promised a system screen; nothing happening is the one outcome
            // that reads as a broken app rather than an unsupported phone.
            Toast.makeText(requireContext(), R.string.default_browser_unavailable,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            // For a result, and that is not a detail. RoleManager.createRequestRoleIntent is
            // documented to be started with startActivityForResult, and started any other way the
            // system declines it without a word — which is exactly what "the button does nothing"
            // looked like. The result itself is ignored; what matters is having asked properly.
            defaultBrowserRequest.launch(request);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.default_browser_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Registered rather than launched ad hoc, because a result contract has to exist before the
     * fragment is started. The list redraws on the way back: saying yes removes the row.
     */
    private final ActivityResultLauncher<Intent> defaultBrowserRequest =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (isAdded()) build();
                    });


    /**
     * Android's own per-app language screen, which is where this lives from Android 13.
     *
     * <p>Handed off rather than reimplemented, and the row says so — the design is explicit that
     * leaving the app should never be a surprise. Below 13 there is no such screen, so the app's
     * details page is the nearest honest destination.
     */
    private void openLanguageSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                startActivity(new Intent(Settings.ACTION_APP_LOCALE_SETTINGS,
                        Uri.fromParts("package", requireContext().getPackageName(), null)));
                return;
            } catch (ActivityNotFoundException ignored) {
                // Falls through to the details page below.
            }
        }
        openAppDetails();
    }

    private void openStorageSettings() {
        openAppDetails();
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", requireContext().getPackageName(), null)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.settings_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ summaries

    /** "English (device)" — the language actually in use, not the one this app was built in. */
    private String currentLanguage() {
        return getString(R.string.setting_language_value,
                getResources().getConfiguration().getLocales().get(0).getDisplayLanguage());
    }

    /**
     * "1.2 GB in 43 videos", off the library rather than off the disk.
     *
     * <p>Counted from what the app knows it saved. Measuring the folder would be a file walk on
     * the main thread for a subtitle nobody is waiting on.
     */
    private String storageSummary() {
        List<DownloadEntity> all = App.get().repository().observeAll().getValue();
        if (all == null) return "";

        int count = 0;
        long bytes = 0;
        for (DownloadEntity d : all) {
            if (d.status != DownloadStatus.COMPLETED) continue;
            count++;
            bytes += d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes;
        }
        if (count == 0) return getString(R.string.setting_storage_empty);
        return getResources().getQuantityString(
                R.plurals.setting_storage_value, count, Formats.bytes(bytes), count);
    }

    /**
     * "1.0 (build 1)".
     *
     * <p>Read from the package manager rather than from BuildConfig. BuildConfig is not generated
     * unless the build asks for it, and asking would mean a change to the gradle file for one
     * line of text — while the package manager already knows, and knows it about the build that
     * is actually installed rather than the one this file was compiled against.
     */
    private String versionSummary() {
        try {
            PackageInfo info = requireContext().getPackageManager().getPackageInfo(requireContext().getPackageName(), 0);
            long code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode;
            return getString(R.string.setting_version_value, info.versionName, code);
        } catch (PackageManager.NameNotFoundException impossible) {
            // The app asking about itself. Kept rather than swallowed, because if this ever
            // happens the version is the least of it.
            Log.w(TAG, "Own package not found", impossible);
            return "";
        }
    }
}
