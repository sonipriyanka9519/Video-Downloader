package com.ms.webview.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;

import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.webkit.Profile;
import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.App;
import com.ms.webview.MainActivity;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.core.Http;
import com.ms.webview.detect.DomScanner;
import com.ms.webview.detect.MediaItem;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.MediaRegistry;
import com.ms.webview.detect.MediaVariant;
import com.ms.webview.detect.NetworkSniffer;
import com.ms.webview.detect.site.SitePolicies;
import com.ms.webview.download.DownloadService;
import com.ms.webview.ui.guide.GuideDialog;
import com.ms.webview.ui.guide.GuideSite;
import com.ms.webview.ui.guide.WalkthroughActivity;
import com.ms.webview.ui.settings.SettingsActivity;
import com.ms.webview.ui.settings.SettingsPrefs;
import com.ms.webview.ui.home.RecentAdapter;
import com.ms.webview.ui.home.SearchHistory;
import com.ms.webview.ui.home.Shortcut;
import com.ms.webview.ui.home.ShortcutAdapter;
import com.ms.webview.ui.home.ShortcutPickerSheet;
import com.ms.webview.ui.home.Shortcuts;
import com.ms.webview.ui.tabs.Tab;
import com.ms.webview.ui.tabs.TabStore;
import com.ms.webview.ui.tabs.TabSwitcherSheet;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The browser tab: a launcher grid of supported sites, and the WebView that replaces it once a
 * page is open. Detection lives here because it is bound to the WebView's lifetime.
 */
public class BrowserFragment extends Fragment
        implements ShortcutAdapter.Listener, ShortcutPickerSheet.Host, TabSwitcherSheet.Host,
        HistorySheet.Host {

    private static final String TAG = "BrowserFragment";

    /**
     * The WebView profile private tabs browse on. One shared by all of them — they are one
     * session, so a site signed into in one private tab is signed into in the next — and deleted
     * whole when the last of them closes.
     */
    private static final String PRIVATE_PROFILE = "private";

    private static final int GRID_COLUMNS = 4;

    /** Where the download button was left, as a fraction of how far it can travel. */
    private static final String FAB_PREFS = "fab_position";
    private static final String KEY_FAB_X = "x";
    private static final String KEY_FAB_Y = "y";

    /**
     * How many tabs keep a live browser at once, the one in front included.
     *
     * <p>A loaded page costs memory whether or not anyone is looking at it, so past a handful the
     * trade reverses: the tabs a viewer actually moves between stay instant, and the ones left at
     * the bottom of the list reload when reopened — which is what every tab did before.
     */
    private static final int LIVE_TAB_LIMIT = 4;

    /**
     * A link handed in from outside — shared from another app, or opened with this one — waiting
     * for the browser to exist.
     *
     * <p>Static because the two ends of the handover cannot see each other. The activity receives
     * the intent, and on a cold start it does so before the pager has built this fragment; by the
     * time the fragment is there, the activity has moved on. A single pending value both can
     * reach is the smallest thing that closes that gap, and it is cleared the moment it is taken
     * so a link is opened once and not again on the next rotation.
     */
    private static final AtomicReference<String> PENDING_URL = new AtomicReference<>();

    /** Called by the activity when a link arrives from another app. */
    public static void openWhenReady(String url) {
        PENDING_URL.set(url);
    }

    /**
     * What the launcher's shortcuts ask for — screen 18, panel E.
     *
     * <p>Parked rather than performed, for the same reason a shared link is: on a cold start this
     * is decided before the pager has built the fragment, and a request handed to a fragment that
     * does not exist is a request lost.
     *
     * <p>Paste link goes through the address screen rather than reading the clipboard here. That
     * screen is the one place in the app that ever reads it, and going anywhere else would be the
     * app helping itself to the clipboard on a launcher tap.
     */
    private static final AtomicBoolean PENDING_SEARCH = new AtomicBoolean();
    private static final AtomicBoolean PENDING_PRIVATE = new AtomicBoolean();

    public static void openSearchWhenReady() {
        PENDING_SEARCH.set(true);
    }

    public static void openPrivateTabWhenReady() {
        PENDING_PRIVATE.set(true);
    }


    /**
     * Set when the app was entered by a shared link, by a link opened with it, or from a download
     * notification: all three arrive with a purpose already, and none of them wants a dialog about
     * the clipboard on top of it.
     *
     * <p>Deferred rather than cancelled, and cleared by the Home button. Pressing Home is the
     * viewer saying they are finished with whatever brought them in, which is exactly the moment
     * the copied link becomes interesting again.
     */
    private static final AtomicBoolean CLIPBOARD_DEFERRED = new AtomicBoolean();

    /**
     * Called by the activity on every entry, saying whether this one came with a purpose of its
     * own. Set both ways, so an ordinary launch clears a deferral left over from the last one.
     */
    public static void deferClipboardPrompt(boolean deferred) {
        CLIPBOARD_DEFERRED.set(deferred);
    }

    /**
     * Raised by the activity each time the app comes to the foreground: ask about becoming the
     * phone's browser. Taken by the browser once it has focus.
     */
    private static final AtomicBoolean ASK_DEFAULT_BROWSER = new AtomicBoolean();

    /** When the offer was last put up, so it is not put up again in the same breath. */
    private static long defaultBrowserAskedAt;

    /**
     * How long an offer stands before the same one may be made again.
     *
     * <p>The offer is meant to be seen every time the app is opened while it is still unanswered.
     * The difficulty is that "opened" and "came to the foreground" are not the same thing: leaving
     * for the search screen, for a guide, or for the settings screen this dialog itself sends
     * people to, all end in the app coming to the foreground again seconds later. Asking again
     * then is not persistence, it is a loop the viewer cannot get out of.
     *
     * <p>A minute separates the two cases well. Nothing anyone does in another screen and comes
     * straight back from lasts that long; putting the phone down and returning to the app does.
     */
    private static final long DEFAULT_BROWSER_QUIET_MS = 60_000L;


    /**
     * Pauses everything the page is playing, and marks what it was so leaving can be undone.
     *
     * <p>Every frame this page can reach, not only the top one: site players are very often an
     * iframe, and a script that paused only its own document left the sound playing from a screen
     * the viewer had already left. Cross-origin frames throw on contentDocument and are skipped —
     * there is no way to reach those, and WebView.onPause is all there is for them.
     *
     * <p>The mark is the point of the loop: returning should restart the one video that was running,
     * not every element on the page. A feed with a dozen reels has one playing and eleven waiting.
     */
    private static final String PAUSE_SCRIPT =
            "(function(){var n=0;"
                    + "function stop(d){try{"
                    + "var m=d.querySelectorAll('video,audio');"
                    + "for(var i=0;i<m.length;i++){var e=m[i];"
                    + "if(!e.paused&&!e.ended){e.__vdResume=true;e.pause();n++;}"
                    + "else{e.__vdResume=false;}}"
                    + "var f=d.querySelectorAll('iframe');"
                    + "for(var j=0;j<f.length;j++){try{if(f[j].contentDocument)"
                    + "stop(f[j].contentDocument);}catch(x){}}"
                    + "}catch(err){}}"
                    + "stop(document);return n;})();";

    /**
     * How long to wait for that script before suspending the browser anyway.
     *
     * <p>Short, because it is running against the app being sent to the background: the script is
     * a few lines over a handful of elements and answers immediately in every ordinary case. This
     * only covers a page torn down mid-navigation, which would otherwise never answer at all.
     */
    private static final long PAUSE_SCRIPT_TIMEOUT_MS = 150L;

    /** Called by the activity as the app comes to the foreground. */
    public static void askAboutDefaultBrowser() {
        ASK_DEFAULT_BROWSER.set(true);
    }

    /** The browser of the tab in front, or null while the grid is showing. */
    @Nullable
    private WebView webView;
    private FrameLayout webContainer;
    private TextView addressBar;
    private ProgressBar pageProgress;
    private RecyclerView shortcutGrid;
    /** The grid and the walkthrough button together: what "the home screen" means here. */
    private View homePanel;
    private ImageView fab;
    private TextView fabBadge;
    /**
     * The address Home closed, so returning to the page can open it again.
     *
     * <p>Null whenever the page in front is live. See showHome, which is where the closing happens
     * and where the reasoning for closing at all is set out.
     */
    @Nullable
    private String closedForHome;

    /** Tinted whole in a private tab, so the state is legible without reading anything. */
    private View browserRoot;
    private View browserToolbar;
    private View addressPill;
    /** The eye-off glyph in the address pill, which names what the colour is saying. */
    private View addressPrivate;

    /** Button and badge together: what is dragged, and what receives the touches. */
    private View fabHolder;
    /** The offer of a walkthrough, on the grid and nowhere else. */
    @Nullable
    /**
     * Home's conditional content — screen 01. Each appears only when it has something to say:
     * the hint on a first run, the recent row once a download exists, the paste card only when
     * a link has actually been offered.
     */
    private View firstRunHint;
    private View recentSection;
    private RecyclerView recentList;
    private RecentAdapter recentAdapter;
    private View pasteCard;
    private TextView pasteText;
    private TextView btnEditShortcuts;
    /** The link the paste card is currently offering, or null when it is hidden. */
    @Nullable
    private String offeredLink;

    /** Held so their enabled state can follow the page's history. See refreshHistoryButtons. */
    private ImageButton btnBack;
    private ImageButton btnForward;
    /** The padlock in the address pill, and the refresh control that becomes an X while loading. */
    private ImageView addressLock;
    private ImageButton btnReload;
    private View pageError;
    /** True while a page is in flight, which is what turns refresh into a stop control. */
    private boolean pageLoading;
    /** The count the FAB last pulsed for, so it swells on a rise and stays still otherwise. */
    private int pulsedAtCount;

    /**
     * The guide belonging to the site in front, or null on a site with none.
     *
     * <p>What the button beside the download button raises. Set when a shortcut with a guide is
     * opened and cleared when the tab is emptied — it follows the page, not the app.
     */
    @Nullable
    private GuideSite currentGuide;
    /** The same guide while it is still waiting for its page to finish loading. */
    @Nullable
    private GuideSite pendingGuide;
    /** The guide while it is on screen, so a second copy cannot open behind it. */
    @Nullable
    private AlertDialog guideDialog;
    /** Raises the guide again once it has been dismissed. */
    private ImageView guideTip;
    private TextView tabCount;

    /**
     * The search screen, and what it chose.
     *
     * <p>A result rather than a callback, because that is what makes leaving it harmless: the
     * browser is told nothing until the screen returns something, so backing out without choosing
     * leaves the page and its address exactly as they were.
     */
    private ActivityResultLauncher<Intent> searchLauncher;

    /**
     * Every open tab, newest last, and which of them is in front.
     *
     * <p>Each keeps its own browser while it is worth keeping loaded, which is what lets the
     * viewer move between tabs without any of them reloading. Past {@link #LIVE_TAB_LIMIT} the
     * least recently used gives its browser up and falls back to an address, a picture and a
     * saved history — enough to come back to where it was, at the cost of fetching it again.
     */
    private final List<Tab> tabs = new ArrayList<>();
    @Nullable
    private String currentTabId;

    private ShortcutAdapter shortcutAdapter;
    /** The registry of the tab in front. Swapped, not cleared, when the tab changes. */
    @Nullable
    private MediaRegistry registry;
    /** Kept so it can be moved from one tab's registry to the next. */
    private Observer<List<MediaItem>> countObserver;

    private OnBackPressedCallback backCallback;

    /** The copied-link offer while it is on screen, so a second one cannot open behind it. */
    @Nullable
    private AlertDialog clipboardDialog;
    /** The default-browser offer, for the same reason and so it can be closed with the view. */
    @Nullable
    private BottomSheetDialog defaultBrowserSheet;
    /** Where the viewer went to answer it, so the answer can be checked when they come back. */
    private ActivityResultLauncher<Intent> defaultBrowserLauncher;
    /** Kept so the window-focus watch can be taken off again with the view. */
    @Nullable
    private ViewTreeObserver.OnWindowFocusChangeListener focusWatch;

    private int readyCount;
    private boolean browsing;

    /** The address the browser last finished loading, as opposed to one the page routed to. */
    private String loadedUrl;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_browser, container, false);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Registered here rather than in onViewCreated: a launcher must exist before the fragment
        // is started, and onViewCreated is already too late for the framework to restore it.
        searchLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK) return;
                    Intent data = result.getData();
                    if (data == null) return;

                    String query = data.getStringExtra(SearchActivity.EXTRA_QUERY);
                    if (!TextUtils.isEmpty(query)) load(query);
                });

        // The result code is not the answer and cannot be trusted as one: the settings screen
        // returns cancelled however it is left, and even the role dialog reports only what was
        // tapped. The state itself is the answer, so it is read back when the viewer returns —
        // and it is read by the ordinary check, so there is only one definition of "default".
        defaultBrowserLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    // Nothing to do either way. If they said yes, the next launch will not ask;
                    // if they did not, it will. This exists so returning does not sit on a
                    // launcher that was never collected.
                });
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        bindViews(view);
        setUpGrid();
        setUpFab();
        setUpBackHandling();
        setUpClipboardWatch(view);
        restoreSession();
    }

    /**
     * Watches for the window getting focus, which is the earliest the clipboard can be read.
     *
     * <p>Not {@code onResume}: from Android 10 the clipboard is only legible to an app whose
     * window has focus, and on a cold start the fragment resumes before the window has it. Reading
     * there returns nothing and returns it silently, which is how a copied link goes unnoticed on
     * exactly the launch that most wants it.
     *
     * <p>It fires again on every return — from the search screen, from a guide, from another app —
     * and that is wanted: a link copied while this app was in the background is picked up the
     * moment the viewer comes back. Offering the same link twice is prevented by remembering it,
     * not by only looking once.
     */
    private void setUpClipboardWatch(View view) {
        focusWatch = focused -> {
            if (!focused) return;
            // One dialog at a time, and the default-browser offer is the one tied to opening the
            // app. The copied link keeps until that has been answered — see its own dismissal.
            if (offerDefaultBrowser()) return;
            offerClipboardLink(false);
        };
        view.getViewTreeObserver().addOnWindowFocusChangeListener(focusWatch);
    }

    private void bindViews(View view) {
        webContainer = view.findViewById(R.id.webContainer);
        addressBar = view.findViewById(R.id.addressBar);
        pageProgress = view.findViewById(R.id.pageProgress);
        shortcutGrid = view.findViewById(R.id.shortcutGrid);
        homePanel = view.findViewById(R.id.homePanel);
        fab = view.findViewById(R.id.fabDownload);
        fabBadge = view.findViewById(R.id.fabBadge);
        browserRoot = view.findViewById(R.id.browserRoot);
        browserToolbar = view.findViewById(R.id.browserToolbar);
        addressPill = view.findViewById(R.id.addressPill);
        addressPrivate = view.findViewById(R.id.addressPrivate);

        fabHolder = view.findViewById(R.id.fabHolder);
        guideTip = view.findViewById(R.id.btnGuideTip);
        tabCount = view.findViewById(R.id.btnTabs);
        tabCount.setOnClickListener(v -> {
            // Photographed on the way in, so the card for the tab being left shows the page as it
            // was a moment ago. Previously a picture was only taken when a tab was switched away
            // from or the app was paused, so the current tab's card was blank until the first
            // time you left it — the one card guaranteed to be looked at was the one with
            // nothing on it.
            stashCurrentTab();
            saveTabs();
            new TabSwitcherSheet().show(getChildFragmentManager(), "tabs");
        });

        view.findViewById(R.id.btnBrowserMenu).setOnClickListener(this::showBrowserMenu);

        ImageButton home = view.findViewById(R.id.btnHome);
        ImageButton back = view.findViewById(R.id.btnBack);
        ImageButton forward = view.findViewById(R.id.btnForward);
        ImageButton reload = view.findViewById(R.id.btnReload);

        // Kept, so their enabled state can be refreshed as history changes. The tint is a
        // selector on state_enabled, so setting enabled is all it takes to grey them.
        btnBack = back;
        btnForward = forward;
        btnReload = reload;
        addressLock = view.findViewById(R.id.addressLock);

        pageError = view.findViewById(R.id.pageError);
        view.findViewById(R.id.btnRetryPage).setOnClickListener(v -> {
            hidePageError();
            if (webView != null) webView.reload();
        });

        // Every one of these drives the WebView, so every one of them has to put the WebView
        // back on screen. Without that, pressing back from the grid navigated the page while it
        // was still hidden behind the grid: the address bar filled in with where you had gone
        // and nothing else happened, which looks exactly like a page that failed to load.

        // The grid, in the tab you are already in. It opened a new tab for a while and that was
        // wrong twice over: pressing it repeatedly left a trail of empty tabs, and the page you
        // were on was pushed into the switcher when all you asked for was to see the grid.
        //
        // Nothing is thrown away. The page stays loaded behind the grid and Back returns to it —
        // see setUpBackHandling. A genuinely new tab is in the overflow and in the switcher.
        //
        // Also where a deferred copied-link offer is finally made: a viewer who came in on a
        // shared link and has now asked for the grid is done with what they arrived with.
        home.setOnClickListener(v -> {
            showHome();
            offerClipboardLink(true);
        });
        // All three act on the tab in front, and a tab showing the grid has no browser at all —
        // so each asks whether there is one before asking it anything.
        back.setOnClickListener(v -> goBack());
        forward.setOnClickListener(v -> {
            if (webView != null && webView.canGoForward()) {
                showBrowser();
                webView.goForward();
            }
        });
        reload.setOnClickListener(v -> {
            // Nothing to reload from the grid; it is not a page.
            if (!browsing || webView == null) return;
            // One control, two jobs, decided by what the page is doing: an X while it is still
            // arriving, refresh once it has. A page that is loading slowly is exactly when
            // somebody wants to stop it, and a refresh button then is the wrong offer.
            if (pageLoading) {
                webView.stopLoading();
                pageLoading = false;
                refreshReloadIcon();
                pageProgress.setVisibility(View.GONE);
            } else {
                webView.reload();
            }
        });

        // Pressed, not typed into. The bar shows where the browser is; where it is going is
        // decided on a screen of its own.
        addressBar.setOnClickListener(v -> openSearch());

        setUpHomeContent(view);
    }

    /**
     * The conditional parts of Home: the first-run hint, the Recent Downloads row, and the
     * paste-link card.
     *
     * <p>None of them render a placeholder. The design is explicit that Home shows less rather
     * than showing a skeleton — a row that has nothing in it is simply not there, which is what
     * keeps a new install looking as light as the MVP.
     */
    private void setUpHomeContent(View view) {
        firstRunHint = view.findViewById(R.id.firstRunHint);
        recentSection = view.findViewById(R.id.recentSection);
        recentList = view.findViewById(R.id.recentList);
        pasteCard = view.findViewById(R.id.pasteCard);
        pasteText = view.findViewById(R.id.pasteText);
        btnEditShortcuts = view.findViewById(R.id.btnEditShortcuts);

        view.findViewById(R.id.btnHowItWorks).setOnClickListener(v -> openHowTo());
        view.findViewById(R.id.btnSeeAllRecent).setOnClickListener(v -> showDownloadsTab());
        btnEditShortcuts.setOnClickListener(v -> toggleEditMode());

        view.findViewById(R.id.pasteOpen).setOnClickListener(v -> {
            String link = offeredLink;
            hidePasteCard();
            if (!TextUtils.isEmpty(link)) {
                ClipboardPrompt.markHandled(requireContext(), link);
                load(link);
            }
        });
        view.findViewById(R.id.pasteDismiss).setOnClickListener(v -> {
            // Declined for good. markHandled is the same eight-link memory the dialog uses, so
            // a link turned down here is not offered again anywhere else either.
            if (!TextUtils.isEmpty(offeredLink)) {
                ClipboardPrompt.markHandled(requireContext(), offeredLink);
            }
            hidePasteCard();
        });

        recentAdapter = new RecentAdapter(this::openDownload);
        recentList.setAdapter(recentAdapter);

        // One source for both surfaces, so Home and the Downloads tab can never disagree about
        // what exists or what is still transferring.
        App.get().repository().observeAll().observe(getViewLifecycleOwner(), this::onLibraryChanged);
    }

    /**
     * Home's two content states, decided by one question: has anything been downloaded?
     *
     * <p>They are mutually exclusive on purpose. The hint is the empty state for the recent row,
     * so showing both would be showing an empty state above its own content.
     */
    private void onLibraryChanged(@Nullable List<DownloadEntity> all) {
        if (recentAdapter == null || !isAdded()) return;

        List<DownloadEntity> recent = new ArrayList<>();
        if (all != null) {
            for (DownloadEntity d : all) {
                recent.add(d);
                if (recent.size() >= RecentAdapter.MAX_ITEMS) break;
            }
        }
        // Private items must never appear here. There is no private flag to filter on yet —
        // the private folder is screen 11 — and this is the point that will need it.

        boolean any = !recent.isEmpty();
        recentAdapter.submit(recent);
        recentSection.setVisibility(any ? View.VISIBLE : View.GONE);
        firstRunHint.setVisibility(any ? View.GONE : View.VISIBLE);
    }

    /** Jiggle-remove mode: the badges appear and the tiles stop opening their sites. */
    private void toggleEditMode() {
        if (shortcutAdapter == null) return;
        boolean editing = !shortcutAdapter.isEditing();
        shortcutAdapter.setEditing(editing);
        btnEditShortcuts.setText(editing ? R.string.home_done : R.string.home_edit);
    }

    /** Offers a copied link on Home as a single line. Never a dialog here. */
    private void showPasteCard(String url) {
        if (pasteCard == null || TextUtils.isEmpty(url)) return;
        offeredLink = url;
        pasteText.setText(getString(R.string.home_link_copied));
        pasteCard.setVisibility(View.VISIBLE);
    }

    private void hidePasteCard() {
        offeredLink = null;
        if (pasteCard != null) pasteCard.setVisibility(View.GONE);
    }

    /**
     * Writes an address into the pill as the design asks for it: the domain, and a padlock when
     * the page came over https.
     *
     * <p>The domain rather than the whole URL. At this width a full address ellipsises into
     * something unreadable, and the part that answers "where am I" is the host. The full URL is
     * still what the search screen is handed when the pill is pressed, so nothing is lost.
     */
    private void showAddress(@Nullable String url) {
        if (addressBar == null) return;
        if (TextUtils.isEmpty(url)) {
            addressBar.setText("");
            if (addressLock != null) addressLock.setVisibility(View.GONE);
            return;
        }
        String host = Formats.hostOf(url);
        addressBar.setText(TextUtils.isEmpty(host) ? url : host);
        if (addressLock != null) {
            // Only for a page actually served securely. A padlock on everything says nothing.
            boolean secure = url.regionMatches(true, 0, "https://", 0, 8);
            addressLock.setVisibility(secure ? View.VISIBLE : View.GONE);
        }
    }

    /** Refresh, or an X while the page is still arriving. */
    private void refreshReloadIcon() {
        if (btnReload == null) return;
        btnReload.setImageResource(pageLoading ? R.drawable.ic_close : R.drawable.ic_refresh);
        btnReload.setContentDescription(getString(pageLoading ? R.string.cancel : R.string.reload));
    }

    /**
     * The page that would not load, as a state rather than a toast.
     *
     * <p>Only for the main frame. A page is full of subresources that fail — a tracker blocked, an
     * advert that times out — and none of them mean the page failed; reporting those would put an
     * error screen over a page that is rendering perfectly well.
     */
    private void showPageError() {
        if (pageError == null || !browsing) return;
        pageError.setVisibility(View.VISIBLE);
    }

    private void hidePageError() {
        if (pageError != null) pageError.setVisibility(View.GONE);
    }

    /**
     * Greys the chevrons that have nowhere to go.
     *
     * <p>Disabled rather than hidden. A control that vanishes when its history empties makes the
     * toolbar reflow under the viewer's finger, and the two chevrons keep moving as they browse;
     * the design asks for the forward one to go faint and stay where it is.
     *
     * <p>On the grid neither has anywhere to go — the page behind it is reached with Back, not
     * with the toolbar — so both go faint until a page is on screen.
     */
    private void refreshHistoryButtons() {
        if (btnBack == null || btnForward == null) return;
        boolean onPage = browsing && webView != null;
        btnBack.setEnabled(onPage && webView.canGoBack());
        btnForward.setEnabled(onPage && webView.canGoForward());
    }

    /** "See all" on the Recent row, and where a still-transferring card sends the viewer. */
    private void showDownloadsTab() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showDownloads();
        }
    }

    /**
     * A card in the Recent row. A finished file opens in the player; one still arriving has
     * nothing to play yet, so it goes to the library where its progress and its controls are.
     */
    private void openDownload(DownloadEntity d) {
        if (d.status == DownloadStatus.COMPLETED && !TextUtils.isEmpty(d.outputUri)) {
            PlayerActivity.open(requireContext(), d.outputUri, d.title);
        } else {
            showDownloadsTab();
        }
    }

    /**
     * Opens the walkthrough, from the button or from the overflow.
     *
     * <p>Screen 14 now, not the single page of steps this used to open. Four pages, and the same four
     * every other route in the app leads to — the design is explicit that there is no second set of
     * help content to keep in step with this one.
     */
    private void openHowTo() {
        WalkthroughActivity.open(requireContext());
    }

    // The walkthrough button that used to sit under the shortcuts is gone. Screen 01 replaces it
    // with the first-run hint card, which carries "How it works" and disappears the moment
    // anything has been downloaded — see setUpHomeContent and onLibraryChanged. It remains in
    // the browser's overflow for anyone who wants it again.

    /**
     * One step back, in the order the viewer would expect it.
     *
     * <p>The grid counts as a step. Pressing Home leaves the page loaded behind it, so the first
     * Back from the grid is a return to that page rather than a departure from the tab — the page
     * is still there, and going back to something still there should not cost anything to fetch.
     *
     * <p>Only once there is nothing left in front does Back empty the tab.
     *
     * @return true when it had somewhere to go, so the caller can decide what to do when it did
     *         not.
     */
    private boolean goBack() {
        Tab tab = currentTab();

        // On the grid with a page still behind it: that page is where Back leads.
        if (!browsing && tab != null && !tab.isBlank() && tab.view != null) {
            webView = tab.view;
            showBrowser();
            return true;
        }

        if (webView != null && webView.canGoBack()) {
            showBrowser();
            webView.goBack();
            return true;
        }

        // Nothing behind this page within its own tab, so the tab goes back to being a new one
        // rather than the browser pretending it has somewhere to go.
        if (browsing || (tab != null && !tab.isBlank())) {
            blankCurrentTab();
            return true;
        }
        return false;
    }

    /** Opens the search screen, telling it where the browser currently is. */
    private void openSearch() {
        Intent intent = new Intent(requireContext(), SearchActivity.class);
        intent.putExtra(SearchActivity.EXTRA_CURRENT_URL, browsing ? loadedUrl : null);
        intent.putExtra(SearchActivity.EXTRA_CURRENT_TITLE,
                webView == null ? null : webView.getTitle());
        searchLauncher.launch(intent);
    }

    /** The browser's own overflow: a new tab, and everywhere it has been. */
    private void showBrowserMenu(View anchor) {
        // Wrapped rather than plain: a popup reads its background off the theme, so this is the
        // only way to give it the same corners as every other surface in the app.
        PopupMenu menu = new PopupMenu(new ContextThemeWrapper(
                requireContext(), R.style.ThemeOverlay_Ds_PopupMenu), anchor);

        menu.getMenu().add(R.string.new_tab).setOnMenuItemClickListener(item -> {
            newTab();
            return true;
        });
        // The only way into private browsing from a standing start. The switcher's segment can
        // only appear once one exists, so without this the mode would be unreachable.
        menu.getMenu().add(R.string.new_private_tab).setOnMenuItemClickListener(item -> {
            newPrivateTab();
            return true;
        });
        menu.getMenu().add(R.string.history).setOnMenuItemClickListener(item -> {
            new HistorySheet().show(getChildFragmentManager(), "history");
            return true;
        });
        // Kept here for good, because the button that offers it disappears after one reading.
        // Hiding help is reasonable; making it unreachable is not.
        menu.getMenu().add(R.string.how_to_download).setOnMenuItemClickListener(item -> {
            openHowTo();
            return true;
        });
        // Last, and the only way in — screen 10 is reached from here rather than from a
        // permanent control, because it is opened once and then not again for weeks.
        menu.getMenu().add(R.string.settings).setOnMenuItemClickListener(item -> {
            SettingsActivity.open(requireContext());
            return true;
        });

        menu.show();
    }


    // ------------------------------------------------------------------ home grid

    private void setUpGrid() {
        // The rebuilt tile from screen 01. The Add sheet keeps the MVP tile until its own turn,
        // which is why the adapter takes the layout rather than naming one.
        shortcutAdapter = new ShortcutAdapter(this, true, false, R.layout.item_ds_shortcut);
        shortcutGrid.setLayoutManager(new GridLayoutManager(requireContext(), GRID_COLUMNS));
        shortcutGrid.setAdapter(shortcutAdapter);
        refreshShortcuts();
    }

    private void refreshShortcuts() {
        shortcutAdapter.submit(Shortcuts.shown(requireContext()));
    }

    /**
     * A shortcut. Every one of them opens its site, and some of them explain it on the way.
     *
     * <p>The site first, always. A guide used to open in front of the shortcut, which meant
     * reading instructions about a page, dismissing them, and only then seeing the page — the
     * explanation and the thing explained were never on screen together. Now the page loads and
     * the guide arrives over it, so the instructions are read against the screen they describe.
     */
    @Override
    public void onOpen(Shortcut shortcut) {
        pendingGuide = GuideSite.forShortcut(shortcut.id);
        currentGuide = pendingGuide;
        load(shortcut.url);
    }

    // ----------------------------------------------------------- the site's own guide

    /**
     * Raises the guide for a site that has just finished loading, if one was waiting.
     *
     * <p>Taken as it is read, so it is raised once for the shortcut that asked for it. A site's
     * pages go on loading as the viewer moves around it, and a guide that reappeared at every one
     * of them would be an argument rather than an explanation.
     */
    private void showPendingGuide() {
        GuideSite site = pendingGuide;
        pendingGuide = null;
        if (site == null || !isResumed() || getContext() == null) return;
        openGuide(site);
    }

    /**
     * Shows one, and puts up the button that brings it back when it goes.
     *
     * <p>The button appears on dismissal rather than alongside the dialog, because until then
     * there is nothing to bring back — and it is bound to every way out, not only the cross, so
     * backing out or tapping beside it leaves the same trail back.
     */
    private void openGuide(GuideSite site) {
        if (guideDialog != null && guideDialog.isShowing()) return;

        guideDialog = GuideDialog.show(requireContext(), site, dialog -> {
            guideDialog = null;
            showGuideTip();
        });
    }

    /**
     * Shows the button that raises the guide again, beside the download button.
     *
     * <p>Beside it, and inside the same holder, so dragging one moves both — and it carries the
     * site's own mark rather than a question mark, because that is the shortest way of saying
     * which guide it is about to open.
     */
    private void showGuideTip() {
        if (guideTip == null || currentGuide == null) return;
        guideTip.setImageResource(currentGuide.icon);
        guideTip.setVisibility(browsing ? View.VISIBLE : View.GONE);
    }

    /** Called when the tab stops being about a guided site: the button has nothing left to open. */
    private void hideGuideTip() {
        currentGuide = null;
        pendingGuide = null;
        if (guideTip != null) guideTip.setVisibility(View.GONE);
    }

    /** From the history screen behind the overflow. */
    @Override
    public void onOpenHistory(SearchHistory.Entry entry) {
        load(entry.url);
    }

    @Override
    public void onAdd() {
        new ShortcutPickerSheet().show(getChildFragmentManager(), "shortcuts");
    }

    @Override
    public void onRemove(Shortcut shortcut) {
        // Nothing is lost by removing one — it goes back into the Add sheet — so this asks
        // rather than warns.
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.remove_shortcut_title)
                .setMessage(getString(R.string.remove_shortcut_message, shortcut.label))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.remove, (d, which) -> {
                    Shortcuts.remove(requireContext(), shortcut);
                    refreshShortcuts();
                })
                .show();
    }

    @Override
    public void onShortcutsChanged() {
        refreshShortcuts();
    }

    /**
     * Back to the grid. The page stays loaded, so returning to it costs nothing.
     *
     * <p>The WebView is hidden, not merely covered. It is the later child of the same frame, so
     * it draws over the grid — and once it has a document, even a blank one, it paints an opaque
     * page and the shortcuts are behind it. That was invisible for as long as a fresh WebView had
     * never been given anything to draw, and stopped being invisible the moment a new tab started
     * loading {@code about:blank} into it.
     */
    private void showHome() {
        browsing = false;
        if (webView != null) {
            webView.setVisibility(View.GONE);
            // The page is closed rather than merely hidden, by request. Hiding it left a video
            // playing behind the grid; stopping it here means Home always means silence.
            //
            // What is remembered is the address, so coming back loads it again — see showBrowser.
            // The cost is real and worth naming: scroll position, anything typed into the page and
            // any state the page was holding go with it. That was the reason for hiding rather than
            // closing, and it was traded away deliberately for a video that stops.
            closedForHome = webView.getUrl();
            webView.loadUrl("about:blank");
        }
        // The panel, not the grid alone: the walkthrough button is part of the home screen and
        // hiding only the grid would leave it floating over whatever page comes next.
        homePanel.setVisibility(View.VISIBLE);
        showAddress(null);
        // The bar measures a page load, and there is no page here. It was only ever cleared by
        // the load finishing, so leaving a still-loading page — which is exactly what backing
        // out of one is — stranded it above the shortcut grid, filling up for a page nobody was
        // looking at any more.
        pageProgress.setVisibility(View.GONE);
        hideKeyboard();
        updateFab();
        refreshHistoryButtons();
        // Home's own content needs no refresh here: the recent row and the first-run hint are
        // driven by the library observer, which fires whenever what they show has changed.
    }

    /**
     * Back to the page, with the bar saying where that is.
     *
     * <p>The address was only ever written while a page was loading. That covers arriving
     * somewhere new and covers stepping back through history, both of which load something — but
     * not simply showing a page that is already open, which is what returning from the grid is.
     * The page came back and the bar stayed empty, reading as though nothing were open.
     */
    private void showBrowser() {
        browsing = true;
        homePanel.setVisibility(View.GONE);
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);

            // Reopened where Home closed it. Taken as it is read, so a second visit to the grid and
            // back does not reload a page that is already there.
            String reopen = closedForHome;
            closedForHome = null;
            if (!TextUtils.isEmpty(reopen)) {
                load(reopen);
                updateFab();
                refreshHistoryButtons();
                return;
            }
            // The browser's own answer first: after a step back through history it is already
            // the page in view, while loadedUrl is only refreshed once the load finishes.
            String showing = webView.getUrl();
            showAddress(TextUtils.isEmpty(showing) ? loadedUrl : showing);
            // Hidden on the way out, so it has to be asked for again on the way back. The
            // WebView knows where it got to; onProgressChanged only fires on the next step, and
            // a page that finished loading while the grid was up would never fire again at all.
            int progress = webView.getProgress();
            pageProgress.setProgress(progress);
            pageProgress.setVisibility(progress >= 100 ? View.GONE : View.VISIBLE);
        }
        updateFab();
        refreshHistoryButtons();
    }

    // -------------------------------------------------------------------- browser

    /**
     * The browser for a tab, built on first use and kept until the tab is closed or reclaimed.
     *
     * <p>One per tab rather than one shared between them, and that is the whole point: a shared
     * browser has to be told to become the other tab, and the only way to tell it is to restore a
     * saved history — which re-fetches the page. Scroll position, script state and a video's
     * position are all lost. A tab that keeps its own browser is simply shown again.
     */
    private WebView webViewFor(Tab tab) {
        if (tab.view != null) return tab.view;

        WebView created = new WebView(requireContext());
        // In case they were left paused. Timers are a process-wide setting, so a browser built
        // after this fragment was paused and destroyed would inherit a frozen clock and never run
        // a line of the page's script — see onPause.
        created.resumeTimers();
        created.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        created.setVisibility(View.GONE);
        // Wired to this tab's registry, not to whichever tab is in front at the time. A page goes
        // on loading and detecting in the background, and its findings must land in its own tab.
        configureWebView(created, App.get().registryFor(tab.id), tab.incognito);

        webContainer.addView(created);
        // The button shares this container and was declared before the page arrived, so a new
        // page would be drawn over it. Lifting it keeps it reachable without moving it.
        if (fabHolder != null) fabHolder.bringToFront();
        tab.view = created;
        reclaimIdleBrowsers();
        return created;
    }

    /**
     * Frees the browsers of tabs left alone longest, once there are more alive than is reasonable.
     *
     * <p>A loaded page costs real memory, and a browser holding one costs it whether or not
     * anyone is looking. Somewhere past a handful the honest trade reverses: a reclaimed tab
     * reloads when reopened, which is the behaviour every tab had before, and the tabs the viewer
     * actually moves between stay instant.
     *
     * <p>Never the tab in front, and never one showing the grid — the second has no browser to
     * reclaim in the first place.
     */
    private void reclaimIdleBrowsers() {
        List<Tab> live = new ArrayList<>();
        for (Tab tab : tabs) {
            if (tab.view != null && !tab.id.equals(currentTabId)) live.add(tab);
        }
        if (live.size() < LIVE_TAB_LIMIT) return;

        Collections.sort(live, (a, b) -> Long.compare(a.lastShownAt, b.lastShownAt));
        for (int i = 0; i <= live.size() - LIVE_TAB_LIMIT; i++) {
            destroyBrowser(live.get(i));
        }
    }

    /** Takes a tab's browser away, leaving the tab itself — its address, title and picture. */
    private void destroyBrowser(Tab tab) {
        WebView view = tab.view;
        if (view == null) return;
        tab.view = null;

        // Its history is worth keeping even though the live page is not: a reopened tab can at
        // least return to where it was rather than to wherever it started.
        Bundle state = new Bundle();
        view.saveState(state);
        tab.state = state;

        webContainer.removeView(view);
        view.setWebChromeClient(null);
        view.destroy();
    }

    private void configureWebView(WebView webView, MediaRegistry registry, boolean incognito) {
        // A scanner and a sniffer of its own, both pointed at this tab's registry. They used to
        // be shared, which was correct while there was one page to describe; with a page per tab
        // a shared pair would post every tab's findings into whichever registry it held.
        final DomScanner domScanner = new DomScanner(requireContext(), registry);
        final NetworkSniffer sniffer = new NetworkSniffer(registry);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // Autoplay is a detection aid, not a convenience: a <video> that never starts never
        // populates currentSrc and never fires the network request we are listening for.
        s.setMediaPlaybackRequiresUserGesture(false);

        // A private tab keeps nothing on disk that it can avoid keeping. Form entries are not
        // remembered, and pages are read from the network rather than served out of — or written
        // into — the on-disk cache.
        Profile profile = null;
        if (incognito) {
            s.setSaveFormData(false);
            s.setCacheMode(WebSettings.LOAD_NO_CACHE);
            profile = usePrivateProfile(webView);
        }

        // The profile's own jar where there is one, the app's where there is not. Asking
        // CookieManager.getInstance() about a profiled browser addresses the wrong store: it is
        // the default profile's manager, so third-party cookies would be permitted on the app's
        // jar and left at their default on the one the tab is actually using.
        CookieManager cookies = profile != null ? profile.getCookieManager()
                : CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        domScanner.install(webView);

        webView.setWebViewClient(new WebViewClient() {
            @Nullable
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                              WebResourceRequest request) {
                // Observe only. Returning null leaves the WebView's own fetch untouched.
                sniffer.inspect(request);
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNonHttpUrl(view, request.getUrl().toString());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                // Subresource failures are routine and must not be reported as page failures.
                if (!request.isForMainFrame() || !isAdded()) return;
                // A state rather than a toast: the message stays, names a likely cause and
                // carries Retry, instead of vanishing and leaving a blank page behind it.
                pageLoading = false;
                refreshReloadIcon();
                pageProgress.setVisibility(View.GONE);
                showPageError();
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // A real navigation invalidates everything found on the previous page.
                registry.startPage(url);
                // Both only while the page is the thing on screen — see onProgressChanged. A
                // page left mid-load carries on loading, and redirects land here as fresh starts.
                pageLoading = true;
                refreshReloadIcon();
                // A new page starts clean: whatever failed last time is no longer on screen.
                hidePageError();
                if (browsing) {
                    showAddress(url);
                    pageProgress.setVisibility(View.VISIBLE);
                }
                hideKeyboard();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // What the browser last actually loaded. An address that turns up afterwards
                // without passing through here was changed by the page's own routing.
                loadedUrl = url;
                pageLoading = false;
                refreshReloadIcon();
                pageProgress.setVisibility(View.GONE);
                if (browsing) showAddress(url);
                registry.updatePageUrl(url);
                domScanner.scanNow(view);
                // Some sites fetch everything before an injected hook can listen, so ask them
                // directly rather than hoping to overhear it.
                registry.resolvePage(url);
                noteTabPage(url, view.getTitle());
                // The guide waits for this: it explains what is on the page, so it is raised once
                // there is a page to explain rather than over a blank one still loading.
                showPendingGuide();
                refreshHistoryButtons();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // SPA route changes land here without a page load.
                registry.updatePageUrl(url);
                // Only while the page is on screen. A feed rewrites its address on every scroll
                // and goes on doing so after the grid is up, so this is the call that refilled a
                // bar showHome had just cleared — and it names a page the user has left.
                if (browsing) showAddress(url);
                domScanner.scanNow(view);
                registry.resolvePage(url);
                maybeReloadForRoute(url);
                // An SPA route change is a history entry without a page load, so this is the
                // only place the chevrons hear about it.
                refreshHistoryButtons();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                // Leaving the page does not stop it loading, so these keep arriving after the
                // grid is up — and each one put the bar back, which is why hiding it on the way
                // out was not enough on its own. The scan below still runs: detection is meant
                // to carry on in the background, it is only the bar that has no business here.
                if (browsing) {
                    pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
                if (newProgress >= 70) domScanner.scanNow(view);
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                noteTabPage(view.getUrl(), title);
                registry.setPageTitle(title);
            }

            /**
             * Lets a protected video play, and nothing else.
             *
             * <p>A page that plays DRM content asks the WebView for
             * {@code PROTECTED_MEDIA_ID} before it can request a licence. Unhandled, the request
             * is denied — the site cannot provision, and reports it the only way it can: Vimeo
             * says "Rights issue, we're having trouble authorizing playback", which reads like
             * the video is unavailable when in fact the browser refused to identify itself.
             *
             * <p>Only that one resource is granted. A {@link PermissionRequest} is also how a
             * page asks for the camera and the microphone, and those are not the browser's to
             * hand over on the user's behalf — anything else on the list is refused.
             *
             * <p>Playing is all this buys. The stream stays encrypted, so it remains
             * undownloadable and the sheet still marks it Protected; the difference is that the
             * viewer can watch it.
             */
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                if (request == null) return;
                String[] wanted = request.getResources();
                if (wanted == null) {
                    request.deny();
                    return;
                }
                for (String resource : wanted) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(resource)) {
                        request.grant(new String[]{resource});
                        return;
                    }
                }
                request.deny();
            }
        });

        webView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.ds_bg));
        webView.setDownloadListener(this::enqueueDirect);

    }

    /**
     * Reloads when a site changed the address without loading anything, on the few platforms
     * that ask for it.
     *
     * <p>Two conditions, and both are needed to keep this from running away. The address must be
     * one the browser did not load itself — otherwise the reload it performs would satisfy its
     * own trigger and go round for ever. And the platform must have asked, because a feed rewrites
     * its address as it scrolls and reloading there would throw the viewer back to the top of the
     * page every few seconds.
     *
     * <p>A third applies only where the platform names its views: the name must have changed too.
     * A site that rewrites its own address after load — a tracking tag, a playlist marker — would
     * otherwise be reloaded for an address change that shows the same video, and the rewrite
     * after that reload would do it again.
     */
    private void maybeReloadForRoute(String url) {
        if (!browsing || TextUtils.isEmpty(url)) return;
        // The browser's own load, arriving here as it always does. Nothing to do.
        if (url.equals(loadedUrl)) return;
        if (!SitePolicies.forHost(Formats.hostOf(url)).reloadOnRouteChange()) return;

        // Null on a site that does not name its views, which leaves those exactly as they were.
        String route = SitePolicies.routeKey(url);
        if (route != null && route.equals(SitePolicies.routeKey(loadedUrl))) return;

        // Claim it before reloading, so the load this starts is not read as a fresh route change.
        loadedUrl = url;
        if (webView != null) webView.reload();
    }

    /**
     * Deals with the schemes a page can redirect to that a WebView cannot render.
     *
     * <p>{@code intent://} links are the usual cause of a page that simply goes blank: sites use
     * them to try to open their native app, and handing the raw URI to ACTION_VIEW fails when
     * the app is absent. These carry a {@code browser_fallback_url} for exactly that case.
     */
    private boolean handleNonHttpUrl(WebView view, String url) {
        if (url.startsWith("http://") || url.startsWith("https://")) return false;

        if (url.startsWith("intent://")) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                String fallback = intent.getStringExtra("browser_fallback_url");
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException notInstalled) {
                    if (!TextUtils.isEmpty(fallback)) view.loadUrl(fallback);
                }
            } catch (Exception ignored) {
                // Malformed intent URI: leave the current page alone rather than blanking it.
            }
            return true;
        }

        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            // mailto:, tel:, a store link with nothing to handle it.
        }
        return true;
    }

    /**
     * Sends the tab in front somewhere, building it a browser if this is its first page.
     *
     * <p>A tab showing the grid has no browser — there is nothing for one to display — so the
     * first address typed into it is also what brings one into being.
     */
    private void load(String input) {
        String url = normalise(input);
        if (url == null) return;

        // What was typed, where it was a search rather than an address. Recorded here because
        // this is the one place that knows which of the two it was — normalise() has already
        // turned a search into a URL by the time anyone downstream sees it.
        //
        // Never from a private tab. What somebody searched for is the most revealing thing this
        // screen handles — more so than the page it led to — and it would otherwise surface as a
        // suggestion under RECENT SEARCHES the next time the address bar was touched, in any tab.
        Tab typing = currentTab();
        boolean incognito = typing != null && typing.incognito;
        if (!incognito && !URLUtil.isNetworkUrl(input.trim())) {
            SearchHistory.recordQuery(requireContext(), input);
        }

        // Opening an address is an answer to the copied-link question whoever asked it. Without
        // this, taking the search screen's own "Link you copied" card would be followed a second
        // later by a dialog offering the link that had just been opened.
        ClipboardPrompt.markHandled(requireContext(), url);

        // Going somewhere else means the last shortcut's guide no longer describes what is on
        // screen. A guide about to be raised is the one exception, and it is the one case where
        // this method was called by the shortcut that set it.
        if (pendingGuide == null) hideGuideTip();

        Tab tab = currentTab();
        if (tab == null) return;
        webView = webViewFor(tab);
        tab.lastShownAt = System.currentTimeMillis();

        showBrowser();
        webView.onResume();
        webView.loadUrl(url);
    }

    /** Anything that looks like a host is loaded; anything else becomes a search. */
    @Nullable
    private String normalise(String input) {
        if (input == null) return null;
        String text = input.trim();
        if (text.isEmpty()) return null;
        if (URLUtil.isValidUrl(text)) return text;
        if (!text.contains(" ") && text.contains(".")) return "https://" + text;
        try {
            return "https://www.google.com/search?q=" + URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return "https://www.google.com/search?q=" + Uri.encode(text);
        }
    }

    // ------------------------------------------------------------------------ FAB

    /**
     * Lets the button be dragged anywhere over the page, and leaves it where it is put.
     *
     * <p>It sits over the page it is about to act on, so wherever it rests it covers something —
     * a caption, a comment, the control the viewer wants next. Fixing it in a corner only chooses
     * whose content it hides. Letting it be moved hands that choice over.
     *
     * <p>Held as a fraction of the space it can move in rather than as a pixel offset, so the
     * corner it was left near is the corner it comes back to on a screen of another size, or
     * after a rotation.
     */
    private void makeFabDraggable() {
        final int slop = ViewConfiguration.get(requireContext()).getScaledTouchSlop();

        // On the button, not on the holder around it. The button is clickable, so it consumes the
        // press itself and the holder never sees a touch — a listener up there was never called,
        // which is the other half of why nothing moved. What is dragged is still the holder, so
        // the badge travels with it.
        fab.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private float startTranslationX;
            private float startTranslationY;
            private boolean dragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        // The holder's translation, not the button's. The listener sits on the
                        // button and the button never moves — reading its translation gave zero
                        // every time, so the first movement of every drag threw the holder back
                        // to where it was first laid out and carried on from there. That is the
                        // jump to the bottom of the screen on touch.
                        startTranslationX = fabHolder.getTranslationX();
                        startTranslationY = fabHolder.getTranslationY();
                        dragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downX;
                        float dy = event.getRawY() - downY;
                        // Not a drag until the finger has travelled far enough to mean it;
                        // otherwise every press would nudge the button a pixel and never open
                        // the sheet.
                        if (!dragging && Math.hypot(dx, dy) < slop) return true;
                        dragging = true;
                        moveFabTo(startTranslationX + dx, startTranslationY + dy);
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (dragging) saveFabPosition();
                        else v.performClick();
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) saveFabPosition();
                        return true;

                    default:
                        return false;
                }
            }
        });

        // Restored on every layout, not once after a post. A post can run before the parent has
        // been measured, and a fraction of a zero-width parent puts the button at an offset that
        // the clamp then reads against the wrong bounds — which is how it came back off screen.
        // Re-applying is free and also survives a rotation, where the bounds genuinely change.
        fabHolder.addOnLayoutChangeListener(
                (v, l, t, r, b, ol, ot, or, ob) -> restoreFabPosition());
    }

    /**
     * Moves the button, keeping every edge of it inside its parent.
     *
     * <p>The limits are worked out from where the button would sit untranslated. It is laid out
     * in the bottom-right corner, so {@code getLeft()} is already most of the way across the
     * screen: the distance it may travel left is that whole offset, and the distance right is
     * whatever margin remains. Reading those the other way round is what pinned it to its corner
     * — the only movement allowed was the width of its own margin.
     */
    private void moveFabTo(float translationX, float translationY) {
        View parent = (View) fabHolder.getParent();
        if (parent == null) return;

        float minX = -fabHolder.getLeft();
        float maxX = parent.getWidth() - fabHolder.getWidth() - fabHolder.getLeft();
        float minY = -fabHolder.getTop();
        float maxY = parent.getHeight() - fabHolder.getHeight() - fabHolder.getTop();

        fabHolder.setTranslationX(Math.max(minX, Math.min(maxX, translationX)));
        fabHolder.setTranslationY(Math.max(minY, Math.min(maxY, translationY)));
    }

    /**
     * Remembers where it was left, as a fraction of the whole page rather than as an offset from
     * a corner — so the place it was put is the place it comes back to on any size of screen.
     */
    private void saveFabPosition() {
        View parent = (View) fabHolder.getParent();
        if (parent == null) return;

        float rangeX = parent.getWidth() - fabHolder.getWidth();
        float rangeY = parent.getHeight() - fabHolder.getHeight();
        if (rangeX <= 0 || rangeY <= 0) return;

        float left = fabHolder.getLeft() + fabHolder.getTranslationX();
        float top = fabHolder.getTop() + fabHolder.getTranslationY();

        fabPrefs().edit()
                .putFloat(KEY_FAB_X, left / rangeX)
                .putFloat(KEY_FAB_Y, top / rangeY)
                .apply();
    }

    private void restoreFabPosition() {
        View parent = (View) fabHolder.getParent();
        // Nothing measured yet, so the fraction has nothing to be a fraction of. The next layout
        // will call this again with real numbers.
        if (parent == null || parent.getWidth() == 0 || fabHolder.getWidth() == 0) return;

        SharedPreferences prefs = fabPrefs();
        if (!prefs.contains(KEY_FAB_X)) return;

        float rangeX = parent.getWidth() - fabHolder.getWidth();
        float rangeY = parent.getHeight() - fabHolder.getHeight();

        moveFabTo(prefs.getFloat(KEY_FAB_X, 0f) * rangeX - fabHolder.getLeft(),
                prefs.getFloat(KEY_FAB_Y, 0f) * rangeY - fabHolder.getTop());
    }

    private SharedPreferences fabPrefs() {
        return requireContext().getSharedPreferences(FAB_PREFS, Context.MODE_PRIVATE);
    }

    private void setUpFab() {
        fab.setOnClickListener(v -> new MediaSheet().show(getChildFragmentManager(), "media"));
        guideTip.setOnClickListener(v -> {
            if (currentGuide != null) openGuide(currentGuide);
        });
        makeFabDraggable();
        countObserver = items -> {
            readyCount = items == null ? 0 : items.size();
            updateFab();
        };
        updateFab();
    }

    /**
     * Points the button at the tab in front.
     *
     * <p>An observer is bound to one registry, and there is now one registry per tab — so
     * changing tab means unsubscribing from what the last tab is finding and subscribing to what
     * this one already found. Without the unsubscribe the badge would keep counting a page the
     * viewer has left.
     */
    private void watchRegistry(MediaRegistry next) {
        if (registry == next) return;
        if (registry != null) registry.live().removeObserver(countObserver);

        registry = next;
        App.get().setCurrentTab(currentTabId);

        if (registry != null) registry.live().observe(getViewLifecycleOwner(), countObserver);
        else {
            readyCount = 0;
            updateFab();
        }
    }

    /**
     * The button answers for the page in front of the user, so on the grid it has nothing to
     * answer for.
     *
     * <p>The count is not merely hidden while the grid is up — the button goes with it. The
     * page it belongs to is still loaded behind the grid and its scanner keeps running, so a
     * video found a moment after leaving would otherwise light the button up over a screen
     * with no video on it, and tapping it would list clips from a page no longer on show.
     */
    private void updateFab() {
        boolean ready = browsing && readyCount > 0;
        // The holder is what is dragged and what receives touches, so it is what has to go when
        // the button does — otherwise an invisible button keeps catching taps meant for the grid.
        if (fabHolder != null) fabHolder.setVisibility(browsing ? View.VISIBLE : View.GONE);
        fab.setVisibility(browsing ? View.VISIBLE : View.GONE);
        fabBadge.setVisibility(ready ? View.VISIBLE : View.GONE);
        fab.setBackgroundResource(ready ? R.drawable.ds_fab_bg : R.drawable.fab_bg_idle);
        if (ready) fabBadge.setText(readyCount > 9 ? "9+" : String.valueOf(readyCount));

        // Detection is silent. The one thing that announces it is a single pulse when the count
        // goes up — no toast, and never a sheet opening by itself over the page. Once, not
        // repeating: a looping animation over somebody's video is the app talking during the
        // film.
        if (ready && readyCount > pulsedAtCount) pulseFab();
        pulsedAtCount = ready ? readyCount : 0;
    }

    /** One 300ms swell. Skipped entirely when the system is set to reduce motion. */
    private void pulseFab() {
        if (fab == null || getContext() == null) return;
        float scale = Settings.Global.getFloat(requireContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale <= 0f) return;

        fab.animate().cancel();
        fab.setScaleX(1f);
        fab.setScaleY(1f);
        long half = getResources().getInteger(R.integer.ds_motion_pulse) / 2;
        fab.animate().scaleX(1.12f).scaleY(1.12f).setDuration(half)
                .withEndAction(() -> {
                    if (fab != null) fab.animate().scaleX(1f).scaleY(1f).setDuration(half).start();
                })
                .start();
    }

    private void enqueueDirect(String url, String userAgent, String contentDisposition,
                               String mimeType, long contentLength) {
        MediaItem item = new MediaItem(url);
        item.pageUrl = webView == null ? loadedUrl : webView.getUrl();
        item.title = URLUtil.guessFileName(url, contentDisposition, mimeType);

        MediaVariant variant = item.addOrGet(url, MediaKind.PROGRESSIVE);
        variant.mime = mimeType;
        variant.sizeBytes = Math.max(0, contentLength);
        variant.headers.put("User-Agent",
                TextUtils.isEmpty(userAgent) ? Http.DEFAULT_UA : userAgent);
        if (!TextUtils.isEmpty(item.pageUrl)) variant.headers.put("Referer", item.pageUrl);
        String cookie = CookieManager.getInstance().getCookie(url);
        if (!TextUtils.isEmpty(cookie)) variant.headers.put("Cookie", cookie);

        boolean waiting = SettingsPrefs.willWaitForWifi(requireContext());
        DownloadService.enqueue(requireContext(), item, variant);
        // Above the tab bar, and owned by the activity — see MainActivity.showDownloadNotice.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showDownloadNotice(
                    getString(waiting ? R.string.queued_waiting_wifi : R.string.queued_toast));
        }
    }

    // ------------------------------------------------------------------------ back

    /**
     * Back walks the page history, then returns to the grid, and only then leaves the app —
     * so the grid is a stop on the way out rather than something you skip past.
     *
     * <p>Only while this tab is the visible one. The fragment stays alive behind the downloads
     * tab, and a browser that swallowed Back from over there would be inexplicable.
     */
    private void setUpBackHandling() {
        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                // The same steps the toolbar's arrow takes, so the two cannot disagree about
                // what "back" means. Only when the browser has nowhere left to go does the
                // press belong to the activity, and leaving the app.
                if (goBack()) return;

                setEnabled(false);
                requireActivity().getOnBackPressedDispatcher().onBackPressed();
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), backCallback);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The private grey is given back whenever this tab steps aside, so coming back to it has
        // to claim it again — otherwise returning from downloads left a private tab looking
        // ordinary until something else happened to redraw the mark.
        showPrivateMark();
        // A pager only resumes the page in view, which is exactly the condition we want.
        if (backCallback != null) backCallback.setEnabled(true);
        // Picked up where it was left, and only when a page is what is showing — a tab on the
        // grid has a browser behind it that should stay stopped until it is looked at again.
        if (webView != null) {
            // Unconditionally, and before anything else decides not to. Timers are paused for the
            // whole process, so a page that is not on screen must still lift them — otherwise the
            // next page loaded after this fragment was paused would never run a line of script.
            webView.resumeTimers();
        }
        if (webView != null && browsing) {
            // Visible again first: it was hidden on the way out, and onResume on a GONE browser
            // leaves a blank page behind the chrome.
            webView.setVisibility(View.VISIBLE);
            webView.onResume();
            resumePlayback();
        }
        openPendingLink();
        openPendingShortcut();

        // The default-browser offer is not raised from here. It is armed once per launch and
        // consumed by the window-focus watch, which is also what orders it ahead of the copied-link
        // dialog — asking from here as well was a second chance at a question that gets one.
    }

    /**
     * Opens a link handed in from another app, in a tab of its own.
     *
     * <p>A new tab rather than the one in front, because a shared link is a new thing to look at
     * and the page already open is one the viewer chose. Taken with {@code getAndSet(null)} so it
     * is opened exactly once — a fragment resumes again on every rotation and every return from
     * the downloads tab, and none of those is a fresh request to open anything.
     */
    private void openPendingLink() {
        // Read once and taken, so it is not opened again on the next resume. Which is exactly
        // why the emptiness check has to come first: this used to require a browser to already
        // exist, and a link shared onto a blank tab — the state the app cold-starts in — was
        // taken from the queue and then dropped on the floor.
        String url = PENDING_URL.getAndSet(null);
        if (TextUtils.isEmpty(url) || webContainer == null) return;
        newTab();
        load(url);
    }

    /**
     * Carries out whichever shortcut was tapped — screen 18, panel E.
     *
     * <p>Taken rather than read, so each happens once. The private tab is opened before the address
     * screen so that a viewer who somehow asked for both types into the private one.
     */
    private void openPendingShortcut() {
        if (!isAdded() || webContainer == null) return;
        if (PENDING_PRIVATE.getAndSet(false)) newPrivateTab();
        // Raised through this fragment's own launcher, so the query comes back here and there is
        // a screen behind it to come back to.
        if (PENDING_SEARCH.getAndSet(false)) openSearch();
    }


    // ---------------------------------------------------------- the default browser

    /**
     * Offers to become the phone's browser, if the app is not it already.
     *
     * <p>Silent when the app already holds the role, when there is nowhere to send the viewer to
     * grant it, while the downloads tab is in front, and inside the quiet period that separates
     * opening the app from merely coming back to it.
     *
     * @return true when the offer is now on screen, so the caller knows not to raise another
     */
    private boolean offerDefaultBrowser() {
        if (!isResumed() || getContext() == null) return false;
        if (defaultBrowserSheet != null && defaultBrowserSheet.isShowing()) return true;
        if (!ASK_DEFAULT_BROWSER.get()) return false;

        // Taken whatever the answer turns out to be: the question has been considered, and
        // leaving it raised would have every later focus reconsider it.
        ASK_DEFAULT_BROWSER.set(false);

        long now = System.currentTimeMillis();
        if (now - defaultBrowserAskedAt < DEFAULT_BROWSER_QUIET_MS) return false;

        // Both checks every time rather than once and remembered. A default browser can be
        // changed from settings at any moment, by us or by anyone else, so the only answer worth
        // acting on is the one the system gives now.
        if (DefaultBrowser.isDefault(requireContext())) return false;
        if (!DefaultBrowser.canAsk(requireContext())) return false;

        defaultBrowserAskedAt = now;
        showDefaultBrowserSheet();
        return true;
    }

    /**
     * Raises the offer from the bottom of the screen.
     *
     * <p>A sheet rather than a dialog, and the difference is not only where it sits: a sheet is
     * something the app is holding out, which can be pushed back down with the thumb already on
     * the screen. Declining is a real answer — the app works perfectly well without the role — so
     * the cross, a swipe down, a back press and a tap outside all mean the same thing and all end
     * in the same place.
     */
    private void showDefaultBrowserSheet() {
        // The sheet itself is shared with the Settings row — see DefaultBrowserSheet. What is left
        // here is only what the browser adds to it: the handle it dismisses by, and the offer that
        // was queued behind this one.
        defaultBrowserSheet = DefaultBrowserSheet.show(requireContext(),
                () -> {
                    defaultBrowserSheet = null;
                    requestDefaultBrowser();
                },
                () -> {
                    // Cleared first, so the offer behind this one is not held back by a sheet on
                    // its way out. Answered, so it may now be asked.
                    defaultBrowserSheet = null;
                    offerClipboardLink(false);
                });
    }

    /**
     * Sends the viewer where they can grant it.
     *
     * <p>Nothing is checked on the way back. The result code is not the answer — the settings
     * screen reports cancelled however it is left — and the state itself is, so the next launch
     * simply asks the system again and stays quiet if the answer has become yes.
     */
    private void requestDefaultBrowser() {
        Intent request = DefaultBrowser.requestIntent(requireContext());
        if (request == null) {
            Toast.makeText(requireContext(),
                    R.string.default_browser_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            defaultBrowserLauncher.launch(request);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(),
                    R.string.default_browser_unavailable, Toast.LENGTH_SHORT).show();
        }
    }

    private void dismissDefaultBrowserSheet() {
        if (defaultBrowserSheet != null) defaultBrowserSheet.dismiss();
        defaultBrowserSheet = null;
    }

    // -------------------------------------------------------------- the copied link

    /**
     * Offers the link on the clipboard, if there is one worth offering.
     *
     * <p>Silent in four cases, each for its own reason. While the downloads tab is in front,
     * because this is the browser's offer to make and a dialog over someone else's screen is an
     * interruption. While one is already showing, because two would stack. While the offer is
     * deferred, unless this is the Home press that lifts it. And when the clipboard holds nothing,
     * something that is not a link, or a link already put to the viewer once — that last is what
     * keeps a declined link declined.
     *
     * @param afterHome true when this is the Home button, which is what a deferred offer waits for
     */
    private void offerClipboardLink(boolean afterHome) {
        // A pager only resumes the fragment in view, so this is also the test for "the browser is
        // the tab on screen" — which is where the offer belongs.
        if (!isResumed() || getContext() == null) return;
        if (clipboardDialog != null && clipboardDialog.isShowing()) return;
        // The default-browser offer belongs to opening the app and goes first; this one is asked
        // as soon as that has been answered.
        if (defaultBrowserSheet != null && defaultBrowserSheet.isShowing()) return;

        if (CLIPBOARD_DEFERRED.get()) {
            if (!afterHome) return;
            CLIPBOARD_DEFERRED.set(false);
        }

        String link = ClipboardPrompt.pending(requireContext());
        if (link == null) return;

        // One shape, always: the dialog. A strip along the top of the page was tried and dropped —
        // an offer that can be missed is an offer that gets missed, and the whole point of reading
        // the clipboard at all is that somebody copied a link meaning to use it here.
        showClipboardDialog(link);
    }


    /**
     * Asks about one copied link.
     *
     * <p>The link is remembered as soon as it is shown rather than when it is answered, so
     * dismissing the dialog by tapping outside counts as an answer too. Anything else would bring
     * it straight back on the next return to the app.
     */
    private void showClipboardDialog(String link) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_clipboard_link, null, false);
        ((TextView) content.findViewById(R.id.clipDialogLink)).setText(link);

        // The design's dialog theme, not the bare builder. Without it the card takes Material's own
        // surface for the host activity — a tinted, slightly grey panel — where the canvas draws a
        // plain white one. Every other dialog in the app already names this; this one was missed.
        clipboardDialog = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setView(content)
                .create();

        ClipboardPrompt.markHandled(requireContext(), link);

        content.findViewById(R.id.btnClipOpen).setOnClickListener(v -> {
            dismissClipboardDialog();
            openCopiedLink(link);
        });
        content.findViewById(R.id.btnClipCancel)
                .setOnClickListener(v -> dismissClipboardDialog());

        clipboardDialog.show();
    }

    private void dismissClipboardDialog() {
        if (clipboardDialog != null) clipboardDialog.dismiss();
        clipboardDialog = null;
    }

    /**
     * Opens the copied link without spending a tab on it needlessly.
     *
     * <p>A new tab where there is a page to keep, since the copied link is a new thing to look at
     * and the page already open is one the viewer chose. The tab in front where it is the grid —
     * an empty tab is not worth preserving, and opening beside it is what leaves a trail of them.
     */
    private void openCopiedLink(String url) {
        Tab tab = currentTab();
        if (tab == null || !tab.isBlank()) newTab();
        load(url);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (backCallback != null) backCallback.setEnabled(false);
        // The status bar goes back to the ordinary surface the moment this stops being the tab in
        // front. The activity root is shared with downloads and settings, and neither of those is
        // private — leaving it grey would mark them as something they are not.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setPrivateChrome(false);
        }
        // The page keeps running when this tab is not the one on screen — timers, scripts and,
        // audibly, any video that was playing. A pager only resumes the page in view, so this is
        // also what happens on the way to the downloads list: the browser goes quiet and comes
        // back where it was, rather than playing on from behind another screen.
        //
        // The elements are paused by name, because WebView.onPause() is documented to stop "extra
        // processing" and in practice does not reliably stop playback — press Home on a playing
        // page and the sound carries on from a screen that is no longer there.
        //
        // The order is the whole of it, and getting it wrong is why this went on happening after it
        // was supposedly fixed. evaluateJavascript is asynchronous: it queues the script and
        // returns. Calling onPause() and pauseTimers() on the next line then suspended the very
        // engine that had yet to run it, so the pause was queued and never executed. They now wait
        // for the script to answer.
        final WebView leaving = webView;
        pauseMediaOf(leaving, () -> {
            if (leaving == null) return;
            leaving.onPause();
            // And the page's clock. A player driven from JavaScript — hls.js fetching its next
            // segment on a timer — is not stopped by pausing the elements alone.
            //
            // Process-wide, not per WebView, which is the trap: left paused it would freeze the
            // next page this app ever loads. onResume lifts it unconditionally for that reason,
            // whether or not there is a WebView to lift it for.
            leaving.pauseTimers();
            // And out of sight, which is the part that was missing. Switching tabs has always
            // stopped a playing video and pressing Home has not, and this is the only difference
            // between the two paths: hideCurrentBrowser sets the outgoing browser GONE. A hidden
            // WebView suspends its own media; a visible one in a backgrounded activity does not.
            leaving.setVisibility(View.GONE);
        });
        // Every other tab that still holds a browser, too. Only the one in front is ever resumed,
        // so a video left playing in a tab behind this one has nothing that would stop it.
        pauseOtherTabs();
        // Persist session cookies: Instagram and Facebook only serve media URLs to a logged-in
        // session, and the downloader replays those cookies out of band.
        CookieManager.getInstance().flush();
        // The picture and the address of whatever is in front, so the switcher has a card for it
        // and the next launch has somewhere to return to.
        stashCurrentTab();
        saveTabs();
    }

    /**
     * Pauses one browser's media, then runs {@code then} — never before.
     *
     * <p>Takes the instance rather than reading the field, because the field moves. Switching tabs
     * reassigns it as part of the same gesture that hides the outgoing browser, so a continuation
     * that read {@code webView} would suspend the tab being <em>opened</em>.
     */
    private void pauseMediaOf(@Nullable WebView view, @Nullable Runnable then) {
        if (view == null) {
            if (then != null) then.run();
            return;
        }

        // Run once, whichever arrives first. A page part-way through a navigation can be torn down
        // without ever answering, and a browser left un-suspended because a callback went missing is
        // the same bug in a different disguise.
        final boolean[] ran = {false};
        final Runnable once = () -> {
            if (ran[0]) return;
            ran[0] = true;
            if (then != null) then.run();
        };
        view.postDelayed(once, PAUSE_SCRIPT_TIMEOUT_MS);
        view.evaluateJavascript(PAUSE_SCRIPT, value -> {
            // How many elements this actually reached. Zero while something is audibly playing
            // means the player is inside a cross-origin frame, where nothing here can touch it
            // and WebView.onPause is the only lever left. Logged rather than guessed at, because
            // the two cases look identical from outside and need different answers.
            Log.d(TAG, "paused " + value + " media element(s) before suspending");
            if ("0".equals(value)) silenceUnreachablePlayer();
            once.run();
        });
    }

    /**
     * Stops anything playing in a tab that is not the one on screen.
     *
     * <p>Each tab keeps its own browser so it can be shown again without refetching the page, and a
     * browser that was never resumed was also never paused — a video started in one tab and left
     * behind by a switch to another goes on playing, and pressing Home would not touch it.
     *
     * <p>No continuation here: these are already suspended as far as the viewer is concerned, so
     * there is nothing to sequence against.
     */
    private void pauseOtherTabs() {
        for (Tab tab : tabs) {
            final WebView other = tab.view;
            if (other == null || other == webView) continue;
            pauseMediaOf(other, other::onPause);
        }
    }


    /**
     * Stops a player this app cannot reach, by taking the audio away from it.
     *
     * <p>Only ever called when the pause script reported it touched nothing <em>and</em> the
     * detector says the page has a video playing. Together those mean one thing: the player is
     * inside a cross-origin frame, where {@code contentDocument} throws and no script of ours can
     * pause it. Vimeo and most embedded players are exactly this.
     *
     * <p>What is left is audio focus. Asking for it permanently tells every media session on the
     * phone to stop, the WebView's included, and abandoning it immediately afterwards leaves the
     * phone as it found it. A permanent gain rather than a transient one on purpose: a transient
     * loss is the signal to pause <em>and resume later</em>, which is precisely what must not
     * happen here.
     *
     * <p>The cost is real and worth stating: another app that was playing is told to stop as well.
     * That is why this is the last resort and not the first — every page whose player can be
     * reached from JavaScript is paused by name, and never reaches this.
     */
    private void silenceUnreachablePlayer() {
        if (!isPlayingSomethingUnreachable()) return;

        Context context = getContext();
        if (context == null) return;
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) return;

        // The legacy call on every version. Its replacement arrived in API 26 and this app runs
        // from 24; the old one is still honoured throughout, and one path is one thing to be sure
        // of rather than two.
        int granted = audio.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.d(TAG, "took audio focus to stop a cross-origin player");
        }
        // Given back at once. Holding it would keep every other app silent for as long as this one
        // sat in the background, which is not ours to do.
        audio.abandonAudioFocus(null);
    }

    /**
     * Whether the page has a video the detector believes is playing.
     *
     * <p>The registry's own answer, the same one that puts NOW PLAYING on a card. Without this
     * check, "the script paused nothing" would also be true of every page with no video at all,
     * and pressing Home on one of those would stop somebody's music for no reason whatsoever.
     */
    private boolean isPlayingSomethingUnreachable() {
        if (currentTabId == null) return false;

        List<MediaItem> items = App.get().registryFor(currentTabId).live().getValue();
        if (items == null) return false;
        for (MediaItem item : items) {
            if (item.current) return true;
        }
        return false;
    }
    /** Starts again exactly what {@link #pauseMediaOf} stopped, and nothing else. */
    private void resumePlayback() {
        if (webView == null) return;
        webView.evaluateJavascript(
                "(function(){"
                        + "function go(d){try{"
                        + "var m=d.querySelectorAll('video,audio');"
                        + "for(var i=0;i<m.length;i++){var e=m[i];"
                        + "if(e.__vdResume){e.__vdResume=false;"
                        // A rejected play() is normal — the page may have reloaded, or the element
                        // may no longer be allowed to start. Swallowed here so it cannot surface
                        // as an unhandled rejection in the page's own console.
                        + "var p=e.play();if(p&&p.catch){p.catch(function(){});}}}"
                        + "var f=d.querySelectorAll('iframe');"
                        + "for(var j=0;j<f.length;j++){try{if(f[j].contentDocument)"
                        + "go(f[j].contentDocument);}catch(x){}}"
                        + "}catch(err){}}"
                        + "go(document);})();", null);
    }

    // ----------------------------------------------------------------------- tabs

    /**
     * Records where the tab in front has got to. Called as pages load, so a tab always knows its
     * own address even though only one WebView exists to hold it.
     */
    /**
     * Marks the chrome when the tab in front is a private one.
     *
     * <p>The pill takes an accent-soft fill and an eye-off glyph appears in front of the address.
     * Only the pill, not the whole screen: the page is still what is being looked at, and private
     * browsing is a fact about the tab rather than a mood for the app.
     */
    private void showPrivateMark() {
        if (browserRoot == null || browserToolbar == null || addressPrivate == null) return;
        // Also reached from tab restoration at launch, which can run before this is attached.
        if (!isAdded()) return;

        Tab tab = currentTab();
        boolean secret = tab != null && tab.incognito;

        // The bar, not a badge on it. A private tab otherwise looks exactly like an ordinary one -
        // same page, same chrome - and "am I still in the private tab" is a question that should be
        // answerable at a glance rather than by remembering.
        //
        // The bar and nothing below it. Tinting the whole column was tried and was too much: the
        // page is where somebody is reading, and recolouring it changed how every site looked
        // without telling them anything the bar was not already saying.
        //
        // So the grey is confined to the chrome, and runs continuously from the status bar through
        // the address bar - one band across the top, which is where a viewer looks to ask what
        // kind of tab this is.
        // The page colour, not the MVP toolbar_surface it used to carry. That was a shade lighter
        // than the page beneath it, so the address bar sat on a visible band of its own.
        browserToolbar.setBackgroundResource(secret ? R.color.ds_private_bg : R.color.ds_bg);
        addressPrivate.setVisibility(secret ? View.VISIBLE : View.GONE);
        // The pill with it: ds_surface_alt is a warm tint, and on the grey bar it read as a patch
        // of some other screen rather than as part of this one.
        if (addressPill != null) {
            addressPill.setBackgroundResource(secret
                    ? R.drawable.ds_bg_pill_private : R.drawable.ds_bg_pill);
        }

        // And the band behind the clock, which is not ours to paint and is the other half of the
        // one continuous grey - see MainActivity.setPrivateChrome.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setPrivateChrome(secret);
        }
    }

    private void noteTabPage(@Nullable String url, @Nullable String title) {
        Tab tab = currentTab();
        if (tab == null || TextUtils.isEmpty(url) || "about:blank".equals(url)) return;

        // A private tab keeps its address in memory so Back and the switcher still work, but
        // nothing about it is written down: no history entry, and no page title, which is the
        // one thing a private card must never be able to show.
        if (tab.incognito) {
            tab.url = url;
            updateTabCount();
            return;
        }

        tab.url = url;
        if (!TextUtils.isEmpty(title)) tab.title = title;
        SearchHistory.record(requireContext(), url, title);
        updateTabCount();
        saveTabs();
    }

    /**
     * Puts the WebView's current page away in the tab that owns it, picture and history both.
     *
     * <p>Called before anything replaces what is on screen. Skipped for a tab showing the grid,
     * which has no page to keep and would otherwise be given a snapshot of the last one.
     */
    private void stashCurrentTab() {
        Tab tab = currentTab();
        if (tab == null) return;

        // History whenever there is a browser to take it from, whatever is on screen. A tab sent
        // to the grid by Home still has its page loaded behind it, and that page's history is
        // what Back walks — losing it because the grid happened to be showing would strand the
        // page the moment its browser was reclaimed.
        if (tab.view != null) {
            Bundle state = new Bundle();
            tab.view.saveState(state);
            tab.state = state;
        }

        // The picture is of whatever the tab is actually showing, which on the grid is the home
        // panel. The panel rather than the grid inside it: the grid is only as tall as its rows,
        // so photographing it would give the switcher a strip where every other card is a page.
        View shown = browsing && webView != null ? webView : homePanel;
        tab.previewPath = TabStore.capture(requireContext(), shown, tab, true);
    }

    /**
     * Takes the blank tab's picture once the grid has been laid out.
     *
     * <p>Posted rather than taken now: the panel was hidden a moment ago and a hidden view has no
     * size, so drawing it immediately produces nothing. One frame later it has one.
     */
    private void captureGridSoon() {
        if (homePanel == null) return;
        homePanel.post(() -> {
            Tab tab = currentTab();
            if (tab == null || !tab.isBlank()) return;
            tab.previewPath = TabStore.capture(requireContext(), homePanel, tab, true);
            saveTabs();
        });
    }

    @Nullable
    private Tab currentTab() {
        for (Tab tab : tabs) {
            if (tab.id.equals(currentTabId)) return tab;
        }
        return null;
    }

    /**
     * Hands the sheet and the button over to a tab's own findings.
     *
     * <p>This used to clear the registry instead, because there was one registry between all the
     * tabs and the findings of the tab being left would otherwise have been offered as the
     * findings of the tab arrived at. Clearing cost the tab being left everything it had, and it
     * could not simply be found again: the payloads a site sends while loading are sent once, so
     * the only way back was to reload the page.
     *
     * <p>Nothing is cleared now. Each tab keeps its own registry for as long as the tab exists,
     * so returning to one shows what it found the first time, immediately and without a reload.
     */
    private void useRegistryOf(Tab tab) {
        watchRegistry(tab == null ? null : App.get().registryFor(tab.id));
        // Every route that changes which tab is in front comes through here — opening one, closing
        // one, restoring the set at launch — so this is the one place the private mark has to be
        // kept in step with.
        showPrivateMark();
    }

    @Override
    public List<Tab> tabs() {
        return tabs;
    }

    @Nullable
    @Override
    public String currentTabId() {
        return currentTabId;
    }

    /**
     * Brings a tab to the front, exactly as it was left.
     *
     * <p>A tab that still has its browser is simply shown: nothing is fetched, nothing reloads,
     * and the page comes back scrolled where it was with whatever it was playing still playing.
     * That is the difference a browser per tab buys, and the reason this used to reload was that
     * there was only one to go round — switching meant handing it a saved history, and restoring
     * a history re-fetches the page.
     *
     * <p>Only a tab whose browser has been reclaimed, or one restored from a previous run of the
     * app, has to load anything — and then from its saved history where there is one, so it
     * returns to the page it was on rather than to wherever it started.
     */
    @Override
    public void openTab(Tab tab) {
        if (tab == null || tab.id.equals(currentTabId)) return;
        stashCurrentTab();
        hideCurrentBrowser();

        currentTabId = tab.id;
        tab.lastShownAt = System.currentTimeMillis();
        useRegistryOf(tab);
        // The guide was raised for a page in the tab being left, so it goes with it. It is not
        // carried across: the tab arrived at is a different page, and the button is about this one.
        hideGuideTip();
        loadedUrl = tab.url;

        if (tab.isBlank()) {
            webView = null;
            showHome();
        } else if (tab.view != null) {
            // Already loaded and still alive. Show it and let it start ticking again.
            webView = tab.view;
            webView.onResume();
            showBrowser();
            loadedUrl = webView.getUrl();
        } else {
            webView = webViewFor(tab);
            showBrowser();
            if (tab.state != null) webView.restoreState(tab.state);
            else webView.loadUrl(tab.url);
        }
        updateTabCount();
        saveTabs();
    }

    /**
     * Takes the tab in front off screen without taking it apart.
     *
     * <p>{@code onPause} rather than nothing, because a hidden page left running keeps its timers
     * ticking, its video decoding and its requests arriving — which costs battery and, since the
     * detector listens to every request the app makes, puts a background tab's media into the
     * sheet of the tab in front. Per instance, not the global {@code pauseTimers}, which would
     * stop every tab including the one being opened.
     */
    private void hideCurrentBrowser() {
        if (webView == null) return;

        // Captured before anything else: the caller reassigns webView to the tab being opened, and
        // this has to go on referring to the one being put away.
        final WebView leaving = webView;
        leaving.setVisibility(View.GONE);
        // Elements first, browser second — the same ordering as onPause and for the same reason. A
        // tab switched away from used to be suspended without its video ever being paused, so it
        // went on playing from behind the tab in front of it.
        pauseMediaOf(leaving, leaving::onPause);
    }

    /**
     * Closes a tab, moving to its neighbour if it was the one in front.
     *
     * <p>Never leaves nothing: closing the last tab opens a fresh one on the grid, which is what
     * a browser with no tabs has to be.
     */
    @Override
    public void closeTab(Tab tab) {
        if (tab == null) return;
        int index = tabs.indexOf(tab);
        if (index < 0) return;

        boolean wasCurrent = tab.id.equals(currentTabId);
        TabStore.forget(requireContext(), tab);
        // The browser goes with the tab. Left behind it would keep its page loaded for a tab
        // that no longer exists.
        if (wasCurrent) {
            webView = null;
            watchRegistry(null);
        }
        destroyBrowser(tab);
        App.get().forgetTab(tab.id);
        tabs.remove(index);

        // "Private tabs and their history vanish when closed" is a promise the switcher makes in
        // writing, so it is kept the moment the last one goes rather than at some later tidy-up.
        if (tab.incognito && !hasPrivateTabs()) clearPrivateData();

        if (tabs.isEmpty()) {
            newTab();
            return;
        }
        if (wasCurrent) {
            // The one that took its place, or the one before it at the end of the list.
            Tab next = tabs.get(Math.min(index, tabs.size() - 1));
            currentTabId = null;   // so openTab does not read this as "already there"
            openTab(next);
            return;
        }
        updateTabCount();
        saveTabs();
    }

    /**
     * Empties the tab in front back to the grid without closing it.
     *
     * <p>What "back" means at the start of a tab's history. Closing the tab would be the other
     * answer and is the wrong one: the viewer asked to leave a page, not to lose a tab.
     */
    private void blankCurrentTab() {
        Tab tab = currentTab();
        if (tab != null) {
            TabStore.forget(requireContext(), tab);
            tab.url = "";
            tab.title = "";
            tab.state = null;
            // The page is gone, so its browser has nothing left to hold.
            destroyBrowser(tab);
            // The tab is empty again, so what it had found is no longer about anything.
            App.get().forgetTab(tab.id);
            watchRegistry(App.get().registryFor(tab.id));
        }
        webView = null;
        loadedUrl = null;
        hideGuideTip();
        showHome();
        captureGridSoon();
        saveTabs();
    }

    /** A tab with no page: the shortcut grid, and nothing carried over from before. */
    @Override
    public void newTab() {
        openNewTab(false);
    }

    /**
     * The same, but private — screen 05.
     *
     * <p>The tab is marked at birth rather than converted later, because the mark is what every
     * privacy decision downstream reads: whether history is written, whether a title is kept,
     * whether a snapshot is taken, whether the tab is saved at all.
     */
    @Override
    public void newPrivateTab() {
        openNewTab(true);
    }

    @Override
    public boolean hasPrivateTabs() {
        for (Tab tab : tabs) {
            if (tab.incognito) return true;
        }
        return false;
    }

    /**
     * Puts a private tab's browser on its own profile, so its cookies are not the app's cookies.
     *
     * <p>This is what makes private browsing mean anything. Without it every tab shares one
     * process-wide {@code CookieManager}: signing into a site privately would sign you in
     * everywhere, and the only way to undo it afterwards would be to clear the whole jar —
     * throwing away the sessions of every ordinary tab along with it.
     *
     * <p>A profile owns its own cookies, storage and cache. Set before the browser loads
     * anything, because that is the only moment it can be set at all.
     *
     * <p>Requires WebView 121 or newer. Where the feature is missing the tab is still private in
     * every other respect — no history, no query, no title, no snapshot, nothing persisted — but
     * its cookies are the shared ones. That is stated rather than papered over: clearing them to
     * compensate would sign the viewer out of their ordinary tabs, which is a worse fault than
     * the one it fixes.
     */
    @Nullable
    private Profile usePrivateProfile(WebView webView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            Log.i(TAG, "This WebView has no profile support; private tabs share the cookie jar");
            return null;
        }
        try {
            Profile profile = ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE);
            WebViewCompat.setProfile(webView, PRIVATE_PROFILE);
            return profile;
        } catch (Exception e) {
            // A profile that cannot be set is not a reason to fail opening the tab.
            Log.w(TAG, "Could not put this tab on the private profile", e);
            return null;
        }
    }

    /**
     * Ends the private session by deleting its profile.
     *
     * <p>Called when the last private tab closes, and only then — deleting on every close would
     * sign the viewer out of a site still open in another private tab.
     *
     * <p>Deleting the profile takes its cookies, its storage and its cache with it, and touches
     * nothing belonging to ordinary tabs. Their logins survive, which is the whole point: a
     * private session ending should not be indistinguishable from clearing the app's data.
     */
    private void clearPrivateData() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) return;
        try {
            // Every private browser has been destroyed by now; a profile still in use refuses.
            ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE);
        } catch (Exception e) {
            Log.w(TAG, "Could not delete the private profile", e);
        }
    }

    private void openNewTab(boolean incognito) {
        stashCurrentTab();
        hideCurrentBrowser();

        Tab tab = new Tab(TabStore.newId());
        tab.incognito = incognito;
        tab.lastShownAt = System.currentTimeMillis();
        tabs.add(tab);
        currentTabId = tab.id;
        // A tab showing the grid has no page, so it is given no browser. One is built the moment
        // it is sent somewhere.
        webView = null;
        loadedUrl = null;
        useRegistryOf(tab);
        hideGuideTip();

        showHome();
        captureGridSoon();
        updateTabCount();
        saveTabs();
    }

    @Override
    public void closeAllTabs() {
        watchRegistry(null);
        boolean hadPrivate = hasPrivateTabs();
        for (Tab tab : tabs) {
            TabStore.forget(requireContext(), tab);
            destroyBrowser(tab);
            App.get().forgetTab(tab.id);
        }
        tabs.clear();
        currentTabId = null;
        webView = null;
        // Closing everything closes the private session too, and it ends the same way it would
        // have one tab at a time — after the browsers are destroyed, so the profile is free.
        if (hadPrivate) clearPrivateData();
        newTab();
    }

    private void updateTabCount() {
        if (tabCount == null) return;
        int open = Math.max(1, tabs.size());
        tabCount.setText(open > 99 ? "∞" : String.valueOf(open));
    }

    private void saveTabs() {
        TabStore.save(requireContext(), tabs, currentTabId);
    }

    /**
     * Reopens the tabs from last time, or starts with one empty tab on the grid.
     *
     * <p>From the saved history where there is one, so the tab comes back with everything behind
     * it still behind it: pressing Back walks the pages that were visited before the app closed,
     * rather than finding an empty stack and leaving for the grid.
     */
    private void restoreSession() {
        tabs.clear();
        tabs.addAll(TabStore.load(requireContext()));

        if (tabs.isEmpty()) {
            newTab();
            return;
        }

        String saved = TabStore.currentId(requireContext());
        Tab open = null;
        for (Tab tab : tabs) {
            if (tab.id.equals(saved)) open = tab;
        }
        if (open == null) open = tabs.get(tabs.size() - 1);

        currentTabId = open.id;
        open.lastShownAt = System.currentTimeMillis();
        useRegistryOf(open);
        if (open.isBlank()) {
            webView = null;
            showHome();
        } else {
            // Opened on Home, not on the page — the same state pressing Home leaves behind.
            //
            // The tab is still here and still knows where it was; what does not happen is the
            // fetch. Coming back to the app is not the same act as asking for a page, and
            // reloading whatever was open last time spends somebody's data on a page they have
            // not asked for yet — and puts a video in front of them, playing, on launch.
            //
            // A shared link or one opened from a notification is a different matter entirely:
            // those arrive with a purpose. They come through openWhenReady and are opened by
            // openPendingLink in their own new tab, over the top of this, so they are unaffected.
            webView = webViewFor(open);
            loadedUrl = open.url;
            showHome();

            // After showHome, not before. showHome writes this field itself from the browser's
            // current address, which on a browser this new is nothing at all — setting it first
            // would have it immediately overwritten with null and the page would be unreachable.
            closedForHome = open.url;
        }
        updateTabCount();
    }

    private void hideKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(
                requireContext(), InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        addressBar.clearFocus();
    }

    /**
     * Every tab's browser, not just the one in front.
     *
     * <p>Each is a view attached to a container that is about to go away, and each holds a
     * loaded page. Destroying only the visible one would leak the rest.
     */
    @Override
    public void onDestroyView() {
        // A dialog outlives the view it was raised from, and a shown one still attached when the
        // window goes is a leaked window.
        dismissClipboardDialog();
        dismissDefaultBrowserSheet();
        if (guideDialog != null) guideDialog.dismiss();
        guideDialog = null;
        if (focusWatch != null && getView() != null) {
            getView().getViewTreeObserver().removeOnWindowFocusChangeListener(focusWatch);
            focusWatch = null;
        }

        webView = null;
        for (Tab tab : tabs) destroyBrowser(tab);
        super.onDestroyView();
    }
}
