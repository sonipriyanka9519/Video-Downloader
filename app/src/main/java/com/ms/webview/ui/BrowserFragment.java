package com.ms.webview.ui;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
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
import com.ms.webview.ui.guide.BaseGuideActivity;
import com.ms.webview.ui.guide.FacebookGuideActivity;
import com.ms.webview.ui.guide.InstagramGuideActivity;
import com.ms.webview.ui.guide.PinterestGuideActivity;
import com.ms.webview.ui.guide.XGuideActivity;
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
    private ImageView fab;
    private TextView fabBadge;
    /** Button and badge together: what is dragged, and what receives the touches. */
    private View fabHolder;
    private TextView tabCount;

    /**
     * The search screen, and what it chose.
     *
     * <p>A result rather than a callback, because that is what makes leaving it harmless: the
     * browser is told nothing until the screen returns something, so backing out without choosing
     * leaves the page and its address exactly as they were.
     */
    private ActivityResultLauncher<Intent> searchLauncher;
    /** The guide screen, and the address it settled on. */
    private ActivityResultLauncher<Intent> guideLauncher;

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

        // A guide hands back either the pasted link or the site itself. Either way it is a new
        // thing to look at, so it gets a tab of its own rather than replacing the grid the
        // shortcut was tapped from.
        guideLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK) return;
                    Intent data = result.getData();
                    if (data == null) return;

                    String url = data.getStringExtra(BaseGuideActivity.EXTRA_URL);
                    if (TextUtils.isEmpty(url)) return;
                    newTab();
                    load(url);
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
        fab = view.findViewById(R.id.fabDownload);
        fabBadge = view.findViewById(R.id.fabBadge);
        fabHolder = view.findViewById(R.id.fabHolder);
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
            webView.reload();
        });

        // Pressed, not typed into. The bar shows where the browser is; where it is going is
        // decided on a screen of its own.
        addressBar.setOnClickListener(v -> openSearch());
    }

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
        PopupMenu menu = new PopupMenu(requireContext(), anchor);

        menu.getMenu().add(R.string.new_tab).setOnMenuItemClickListener(item -> {
            newTab();
            return true;
        });
        menu.getMenu().add(R.string.history).setOnMenuItemClickListener(item -> {
            new HistorySheet().show(getChildFragmentManager(), "history");
            return true;
        });

        menu.show();
    }


    // ------------------------------------------------------------------ home grid

    private void setUpGrid() {
        shortcutAdapter = new ShortcutAdapter(this, true);
        shortcutGrid.setLayoutManager(new GridLayoutManager(requireContext(), GRID_COLUMNS));
        shortcutGrid.setAdapter(shortcutAdapter);
        refreshShortcuts();
    }

    private void refreshShortcuts() {
        shortcutAdapter.submit(Shortcuts.shown(requireContext()));
    }

    /**
     * A shortcut, which for some sites is a guide first.
     *
     * <p>The sites people arrive with a copied link for are the ones worth explaining, and
     * dropping straight onto a logged-out home page teaches nothing. Everything else opens
     * directly — a screen to dismiss is not help.
     */
    @Override
    public void onOpen(Shortcut shortcut) {
        Class<?> guide = guideFor(shortcut.id);
        if (guide == null) {
            load(shortcut.url);
            return;
        }
        guideLauncher.launch(new Intent(requireContext(), guide));
    }

    /**
     * The guide screen for a site, or null where there is none.
     *
     * <p>Null is the ordinary answer. Most sites need no explaining — you open them and scroll —
     * and a screen you only dismiss is not help.
     */
    @Nullable
    private static Class<?> guideFor(@Nullable String shortcutId) {
        if (shortcutId == null) return null;
        switch (shortcutId) {
            case "facebook":
                return FacebookGuideActivity.class;
            case "instagram":
                return InstagramGuideActivity.class;
            case "pinterest":
                return PinterestGuideActivity.class;
            case "x":
                return XGuideActivity.class;
            default:
                return null;
        }
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
        new MaterialAlertDialogBuilder(requireContext())
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
        if (webView != null) webView.setVisibility(View.GONE);
        shortcutGrid.setVisibility(View.VISIBLE);
        addressBar.setText("");
        hideKeyboard();
        updateFab();
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
        shortcutGrid.setVisibility(View.GONE);
        if (webView != null) {
            webView.setVisibility(View.VISIBLE);
            // The browser's own answer first: after a step back through history it is already
            // the page in view, while loadedUrl is only refreshed once the load finishes.
            String showing = webView.getUrl();
            addressBar.setText(TextUtils.isEmpty(showing) ? loadedUrl : showing);
        }
        updateFab();
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
        created.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        created.setVisibility(View.GONE);
        // Wired to this tab's registry, not to whichever tab is in front at the time. A page goes
        // on loading and detecting in the background, and its findings must land in its own tab.
        configureWebView(created, App.get().registryFor(tab.id));

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

    private void configureWebView(WebView webView, MediaRegistry registry) {
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

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

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
                Toast.makeText(requireContext(), R.string.page_load_failed,
                        Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                // A real navigation invalidates everything found on the previous page.
                registry.startPage(url);
                addressBar.setText(url);
                pageProgress.setVisibility(View.VISIBLE);
                hideKeyboard();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // What the browser last actually loaded. An address that turns up afterwards
                // without passing through here was changed by the page's own routing.
                loadedUrl = url;
                pageProgress.setVisibility(View.GONE);
                registry.updatePageUrl(url);
                domScanner.scanNow(view);
                // Some sites fetch everything before an injected hook can listen, so ask them
                // directly rather than hoping to overhear it.
                registry.resolvePage(url);
                noteTabPage(url, view.getTitle());
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // SPA route changes land here without a page load.
                registry.updatePageUrl(url);
                addressBar.setText(url);
                domScanner.scanNow(view);
                registry.resolvePage(url);
                maybeReloadForRoute(url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                pageProgress.setProgress(newProgress);
                pageProgress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
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

        webView.setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.surface));
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
        if (!URLUtil.isNetworkUrl(input.trim())) {
            SearchHistory.recordQuery(requireContext(), input);
        }

        // Opening an address is an answer to the copied-link question whoever asked it. Without
        // this, taking the search screen's own "Link you copied" card would be followed a second
        // later by a dialog offering the link that had just been opened.
        ClipboardPrompt.markHandled(requireContext(), url);

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
        fab.setBackgroundResource(ready ? R.drawable.fab_bg_active : R.drawable.fab_bg_idle);
        if (ready) fabBadge.setText(readyCount > 9 ? "9+" : String.valueOf(readyCount));
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

        DownloadService.enqueue(requireContext(), item, variant);
        Toast.makeText(requireContext(), R.string.queued_toast, Toast.LENGTH_SHORT).show();
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
        // A pager only resumes the page in view, which is exactly the condition we want.
        if (backCallback != null) backCallback.setEnabled(true);
        openPendingLink();

        // The default-browser offer needs no clipboard and so needs no window focus, and this is
        // the one path that focus does not cover: the app opening onto the downloads tab leaves
        // the question raised until the browser is looked at, and moving between pager tabs is
        // not a focus change. Posted so the fragment has finished resuming before it is asked
        // whether it has — that answer is also how it tells it is the tab in front.
        if (getView() != null) getView().post(this::offerDefaultBrowser);
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
     * the screen. "Later" is a real answer either way — the app works perfectly well without the
     * role — so swiping it down, backing out and tapping outside all mean the same thing and all
     * end in the same place.
     */
    private void showDefaultBrowserSheet() {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.sheet_default_browser, null, false);

        defaultBrowserSheet = new BottomSheetDialog(requireContext());
        defaultBrowserSheet.setContentView(content);

        // The sheet's own container is opaque and square. Left as it is, its corners sit behind
        // the rounded ones this layout draws and the rounding is only visible as two white
        // triangles — so the container gets out of the way and the layout provides the surface.
        View container = defaultBrowserSheet
                .findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (container != null) container.setBackgroundColor(Color.TRANSPARENT);

        content.findViewById(R.id.btnSetDefault).setOnClickListener(v -> {
            dismissDefaultBrowserSheet();
            requestDefaultBrowser();
        });
        content.findViewById(R.id.btnSetDefaultLater).setOnClickListener(v -> {
            dismissDefaultBrowserSheet();
            // Answered, so whatever was waiting behind it may now be asked.
            offerClipboardLink(false);
        });
        // Cleared first, so the offer behind this one is not held back by a sheet on its way out.
        defaultBrowserSheet.setOnCancelListener(d -> {
            defaultBrowserSheet = null;
            offerClipboardLink(false);
        });

        defaultBrowserSheet.show();
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

        clipboardDialog = new MaterialAlertDialogBuilder(requireContext())
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
        // Persist session cookies: Instagram and Facebook only serve media URLs to a logged-in
        // session, and the downloader replays those cookies out of band.
        CookieManager.getInstance().flush();
        // The picture and the address of whatever is in front, so the switcher has a card for it
        // and the next launch has somewhere to return to.
        stashCurrentTab();
        saveTabs();
    }

    // ----------------------------------------------------------------------- tabs

    /**
     * Records where the tab in front has got to. Called as pages load, so a tab always knows its
     * own address even though only one WebView exists to hold it.
     */
    private void noteTabPage(@Nullable String url, @Nullable String title) {
        Tab tab = currentTab();
        if (tab == null || TextUtils.isEmpty(url) || "about:blank".equals(url)) return;
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

        // The picture is of whatever the tab is actually showing, which on the grid is the grid.
        View shown = browsing && webView != null ? webView : shortcutGrid;
        tab.previewPath = TabStore.capture(requireContext(), shown, tab, true);
    }

    /**
     * Takes the blank tab's picture once the grid has been laid out.
     *
     * <p>Posted rather than taken now: the grid was hidden a moment ago and a hidden view has no
     * size, so drawing it immediately produces nothing. One frame later it has one.
     */
    private void captureGridSoon() {
        if (shortcutGrid == null) return;
        shortcutGrid.post(() -> {
            Tab tab = currentTab();
            if (tab == null || !tab.isBlank()) return;
            tab.previewPath = TabStore.capture(requireContext(), shortcutGrid, tab, true);
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
        webView.setVisibility(View.GONE);
        webView.onPause();
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
        showHome();
        captureGridSoon();
        saveTabs();
    }

    /** A tab with no page: the shortcut grid, and nothing carried over from before. */
    @Override
    public void newTab() {
        stashCurrentTab();
        hideCurrentBrowser();

        Tab tab = new Tab(TabStore.newId());
        tab.lastShownAt = System.currentTimeMillis();
        tabs.add(tab);
        currentTabId = tab.id;
        // A tab showing the grid has no page, so it is given no browser. One is built the moment
        // it is sent somewhere.
        webView = null;
        loadedUrl = null;
        useRegistryOf(tab);

        showHome();
        captureGridSoon();
        updateTabCount();
        saveTabs();
    }

    @Override
    public void closeAllTabs() {
        watchRegistry(null);
        for (Tab tab : tabs) {
            TabStore.forget(requireContext(), tab);
            destroyBrowser(tab);
            App.get().forgetTab(tab.id);
        }
        tabs.clear();
        currentTabId = null;
        webView = null;
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
            webView = webViewFor(open);
            loadedUrl = open.url;
            showBrowser();
            // The same choice openTab makes: history if we have it, address if we do not.
            if (open.state != null) webView.restoreState(open.state);
            else webView.loadUrl(open.url);
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
        if (focusWatch != null && getView() != null) {
            getView().getViewTreeObserver().removeOnWindowFocusChangeListener(focusWatch);
            focusWatch = null;
        }

        webView = null;
        for (Tab tab : tabs) destroyBrowser(tab);
        super.onDestroyView();
    }
}
