package com.ms.webview.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
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
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

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
import com.ms.webview.ui.home.Shortcut;
import com.ms.webview.ui.home.ShortcutAdapter;
import com.ms.webview.ui.home.ShortcutPickerSheet;
import com.ms.webview.ui.home.Shortcuts;
import com.ms.webview.ui.tabs.Tab;
import com.ms.webview.ui.tabs.TabStore;
import com.ms.webview.ui.tabs.TabSwitcherSheet;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The browser tab: a launcher grid of supported sites, and the WebView that replaces it once a
 * page is open. Detection lives here because it is bound to the WebView's lifetime.
 */
public class BrowserFragment extends Fragment
        implements ShortcutAdapter.Listener, ShortcutPickerSheet.Host, TabSwitcherSheet.Host {

    private static final int GRID_COLUMNS = 4;

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

    private WebView webView;
    private EditText addressBar;
    private ProgressBar pageProgress;
    private RecyclerView shortcutGrid;
    private ImageView fab;
    private TextView fabBadge;
    private TextView tabCount;

    private View clipPanel;
    private View clipCard;
    private View clipDetail;
    private TextView clipLabel;
    private TextView clipTitle;
    private TextView clipUrl;
    private View pageCard;
    private ImageView pageIcon;
    private TextView pageTitle;
    private TextView pageUrl;
    private ImageView clipIcon;
    /** The current page's own icon, as the browser reported it. */
    @Nullable
    private Bitmap pageFavicon;
    private ShortcutAdapter omniShortcutAdapter;
    /** The link the panel is offering, or null when the clipboard holds something else. */
    @Nullable
    private String clipboardLink;
    /** Everything the clipboard holds, link or plain text, as offered by the middle row. */
    @Nullable
    private String clipboardText;

    /**
     * Every open tab, newest last, and which of them is in front.
     *
     * <p>One WebView serves all of them: the tab in front holds it, and the rest keep an address,
     * a picture and — within a session — their history. A WebView per tab would keep each page
     * live, and would cost a page's worth of memory and background work per tab to do it.
     */
    private final List<Tab> tabs = new ArrayList<>();
    @Nullable
    private String currentTabId;

    private ShortcutAdapter shortcutAdapter;
    private MediaRegistry registry;
    private NetworkSniffer sniffer;
    private DomScanner domScanner;

    private OnBackPressedCallback backCallback;

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
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        registry = App.get().registry();
        sniffer = new NetworkSniffer(registry);

        bindViews(view);
        setUpGrid();
        setUpWebView();
        setUpFab();
        setUpBackHandling();
        restoreSession();
    }

    private void bindViews(View view) {
        webView = view.findViewById(R.id.webView);
        addressBar = view.findViewById(R.id.addressBar);
        pageProgress = view.findViewById(R.id.pageProgress);
        shortcutGrid = view.findViewById(R.id.shortcutGrid);
        fab = view.findViewById(R.id.fabDownload);
        fabBadge = view.findViewById(R.id.fabBadge);
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

        ImageButton home = view.findViewById(R.id.btnHome);
        ImageButton back = view.findViewById(R.id.btnBack);
        ImageButton forward = view.findViewById(R.id.btnForward);
        ImageButton reload = view.findViewById(R.id.btnReload);

        // Every one of these drives the WebView, so every one of them has to put the WebView
        // back on screen. Without that, pressing back from the grid navigated the page while it
        // was still hidden behind the grid: the address bar filled in with where you had gone
        // and nothing else happened, which looks exactly like a page that failed to load.

        // A new tab, not a peek at the grid over the page you were on — the page you left stays
        // open, one tap away in the switcher. Pressing it from a tab that is already blank does
        // nothing but return to the grid: there is no page to leave, and a second empty tab is
        // not what anyone was asking for.
        home.setOnClickListener(v -> {
            Tab open = currentTab();
            if (open != null && open.isBlank()) {
                showHome();
            } else {
                newTab();
            }
        });
        back.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                showBrowser();
                webView.goBack();
            } else {
                // Nothing behind this page within its own tab, so the tab goes back to being
                // a new one rather than the browser pretending it has somewhere to go.
                blankCurrentTab();
            }
        });
        forward.setOnClickListener(v -> {
            if (webView.canGoForward()) {
                showBrowser();
                webView.goForward();
            }
        });
        reload.setOnClickListener(v -> {
            // Nothing to reload from the grid; it is not a page.
            if (!browsing) return;
            webView.reload();
        });

        bindClipboardSuggestion(view);

        addressBar.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (go) {
                load(addressBar.getText().toString());
                hideKeyboard();
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------- copied-link suggestion

    /**
     * Offers whatever link is on the clipboard, the moment the address bar is touched.
     *
     * <p>Almost every visit to this browser starts with a link copied somewhere else, and typing
     * it back in by hand is the one thing a phone is worst at. Reading the clipboard only while
     * the address bar has focus is deliberate: it is the one moment the user has asked for
     * somewhere to put a link, and on recent Android every read is announced to them.
     */
    private void bindClipboardSuggestion(View view) {
        clipPanel = view.findViewById(R.id.clipPanel);
        clipCard = view.findViewById(R.id.clipCard);
        clipDetail = view.findViewById(R.id.clipDetail);
        clipLabel = view.findViewById(R.id.clipLabel);
        clipTitle = view.findViewById(R.id.clipTitle);
        clipUrl = view.findViewById(R.id.clipUrl);

        pageCard = view.findViewById(R.id.pageCard);
        pageIcon = view.findViewById(R.id.pageIcon);
        pageTitle = view.findViewById(R.id.pageTitle);
        pageUrl = view.findViewById(R.id.pageUrl);
        clipIcon = view.findViewById(R.id.clipIcon);

        // The page you are on, and the same three actions the copied link gets.
        view.findViewById(R.id.pageOpen).setOnClickListener(v -> {
            hideClipboardSuggestion();
            hideKeyboard();
        });
        view.findViewById(R.id.pageShare).setOnClickListener(v -> shareLink(loadedUrl));
        view.findViewById(R.id.pageCopy).setOnClickListener(v -> copyLink(loadedUrl));
        view.findViewById(R.id.pageEdit).setOnClickListener(v -> {
            if (TextUtils.isEmpty(loadedUrl)) return;
            addressBar.setText(loadedUrl);
            addressBar.setSelection(loadedUrl.length());
            hideClipboardSuggestion();
        });

        // The places the user actually goes, along the bottom — the same catalogue as the home
        // grid, laid out in a row because here it is a suggestion rather than a destination.
        RecyclerView omniShortcuts = view.findViewById(R.id.omniShortcuts);
        omniShortcutAdapter = new ShortcutAdapter(this, false, true);
        omniShortcuts.setLayoutManager(new LinearLayoutManager(
                requireContext(), LinearLayoutManager.HORIZONTAL, false));
        omniShortcuts.setAdapter(omniShortcutAdapter);

        view.findViewById(R.id.clipRow).setOnClickListener(v -> openClipboardLink());
        view.findViewById(R.id.clipOpen).setOnClickListener(v -> openClipboardLink());

        // Reveal, rather than open. The offer is one line until the user asks what it is.
        view.findViewById(R.id.clipReveal).setOnClickListener(v ->
                clipDetail.setVisibility(clipDetail.getVisibility() == View.VISIBLE
                        ? View.GONE : View.VISIBLE));

        view.findViewById(R.id.clipShare).setOnClickListener(v -> shareLink(clipboardLink));
        view.findViewById(R.id.clipCopy).setOnClickListener(v -> copyLink(clipboardLink));
        // Edit hands the link to the address bar and leaves the keyboard up, so the panel gets
        // out of the way of the thing it just started.
        view.findViewById(R.id.clipEdit).setOnClickListener(v -> {
            if (TextUtils.isEmpty(clipboardLink)) return;
            addressBar.setText(clipboardLink);
            addressBar.setSelection(clipboardLink.length());
            hideClipboardSuggestion();
        });

        addressBar.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) showClipboardSuggestion();
            else hideClipboardSuggestion();
        });
        // Typing replaces the suggestion: the user has said what they want instead.
        addressBar.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (addressBar.hasFocus() && s.length() > 0) hideClipboardSuggestion();
            }
        });
    }

    /**
     * Fills the three rows and shows whichever of them have something to say.
     *
     * <p>The panel appears whenever the address bar is focused, even with an empty clipboard: the
     * shortcuts alone are worth the space, and a row of familiar icons is a faster answer to
     * "where next" than typing is.
     */
    private void showClipboardSuggestion() {
        // The page in front, where there is one. On the grid there is nothing to describe.
        boolean onPage = browsing && !TextUtils.isEmpty(loadedUrl);
        if (onPage) {
            String title = webView == null ? null : webView.getTitle();
            pageTitle.setText(TextUtils.isEmpty(title) ? Formats.hostOf(loadedUrl) : title);
            pageUrl.setText(loadedUrl);
            showSiteIcon(pageIcon, loadedUrl, pageFavicon);
        }
        pageCard.setVisibility(onPage ? View.VISIBLE : View.GONE);

        clipboardLink = clipboardLink();
        clipboardText = clipboardLink != null ? clipboardLink : clipboardText();
        if (!TextUtils.isEmpty(clipboardText)) {
            // "Link" or "Text", because the clipboard holds both and the offer differs: one is
            // somewhere to go, the other is something to search for.
            clipLabel.setText(clipboardLink != null
                    ? R.string.link_you_copied : R.string.text_you_copied);
            // Collapsed again each time: the previous reveal was about a previous clipboard.
            clipDetail.setVisibility(View.GONE);
            clipTitle.setText(clipboardLink != null
                    ? titleForLink(clipboardLink) : clipboardText);
            clipUrl.setText(clipboardText);
            // Copied text is not a site, so it keeps the globe.
            showSiteIcon(clipIcon, clipboardLink, null);
        }
        clipCard.setVisibility(TextUtils.isEmpty(clipboardText) ? View.GONE : View.VISIBLE);

        omniShortcutAdapter.submit(Shortcuts.shown(requireContext()));
        clipPanel.setVisibility(View.VISIBLE);
    }

    private void hideClipboardSuggestion() {
        if (clipPanel != null) clipPanel.setVisibility(View.GONE);
    }

    /** Opens a copied link, or searches for copied text — {@code load} decides which it is. */
    private void openClipboardLink() {
        if (TextUtils.isEmpty(clipboardText)) return;
        String target = clipboardText;
        hideClipboardSuggestion();
        hideKeyboard();
        load(target);
    }

    /**
     * Puts a face on a link: the site's own mark where we have one, the page's favicon where the
     * browser has fetched it, and a globe when neither.
     *
     * <p>The catalogue is asked first even though the favicon is more specific, because the
     * catalogue's marks are drawn for this app at the size they are shown, while a favicon is a
     * 16-pixel bitmap the site happened to ship and looks it when scaled up.
     */
    private void showSiteIcon(ImageView target, @Nullable String url, @Nullable Bitmap favicon) {
        int brand = url == null ? 0 : Shortcuts.iconForUrl(url);
        if (brand != 0) {
            target.setImageResource(brand);
            return;
        }
        if (favicon != null && !favicon.isRecycled()) {
            target.setImageBitmap(favicon);
            return;
        }
        target.setImageResource(R.drawable.ic_globe);
    }

    /** Whatever is on the clipboard, link or not, trimmed to something a row can show. */
    @Nullable
    private String clipboardText() {
        try {
            ClipboardManager clipboard = ContextCompat.getSystemService(
                    requireContext(), ClipboardManager.class);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;

            CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
            if (text == null) return null;

            String trimmed = text.toString().trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The link on the clipboard, or null when there is not one.
     *
     * <p>Only an address is offered. The clipboard is as often a phone number or a paragraph of
     * text, and offering those as somewhere to navigate is noise.
     */
    @Nullable
    private String clipboardLink() {
        try {
            ClipboardManager clipboard = ContextCompat.getSystemService(
                    requireContext(), ClipboardManager.class);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;

            CharSequence text = clip.getItemAt(0).coerceToText(requireContext());
            if (text == null) return null;

            String candidate = text.toString().trim();
            if (!URLUtil.isNetworkUrl(candidate)) return null;
            // Already where the user is; offering it would be an odd thing to suggest.
            return candidate.equals(loadedUrl) ? null : candidate;
        } catch (Exception e) {
            // A clipboard the system will not hand over is simply one with no suggestion in it.
            return null;
        }
    }

    /**
     * A name for the link. The title of an open tab pointing at it, where one exists — that is a
     * page we have actually loaded and read the title of — and otherwise the host, which is
     * short, true, and better than repeating the address twice.
     */
    private String titleForLink(String url) {
        for (Tab tab : tabs) {
            if (url.equals(tab.url) && !TextUtils.isEmpty(tab.title)) return tab.title;
        }
        String host = Formats.hostOf(url);
        return TextUtils.isEmpty(host) ? url : host;
    }

    private void copyLink(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return;
        ClipboardManager clipboard = ContextCompat.getSystemService(
                requireContext(), ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url));
        Toast.makeText(requireContext(), R.string.link_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareLink(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_link_via)));
        } catch (ActivityNotFoundException e) {
            // No chooser on the device, which is not something to crash over.
        }
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

    @Override
    public void onOpen(Shortcut shortcut) {
        // Reached from the home grid or from the row under a focused address bar. In the second
        // case the panel and the keyboard are still up, and the answer has just been given.
        hideClipboardSuggestion();
        hideKeyboard();
        load(shortcut.url);
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
        webView.setVisibility(View.GONE);
        shortcutGrid.setVisibility(View.VISIBLE);
        addressBar.setText("");
        hideKeyboard();
        updateFab();
    }

    private void showBrowser() {
        browsing = true;
        shortcutGrid.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        updateFab();
    }

    // -------------------------------------------------------------------- browser

    private void setUpWebView() {
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

        domScanner = new DomScanner(requireContext(), registry);
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

            /** Kept for the address bar's page card, for sites the app carries no mark for. */
            @Override
            public void onReceivedIcon(WebView view, Bitmap icon) {
                pageFavicon = icon;
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
        webView.reload();
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

    private void load(String input) {
        String url = normalise(input);
        if (url == null) return;
        showBrowser();
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

    private void setUpFab() {
        fab.setOnClickListener(v -> new MediaSheet().show(getChildFragmentManager(), "media"));
        registry.live().observe(getViewLifecycleOwner(), (List<MediaItem> items) -> {
            readyCount = items == null ? 0 : items.size();
            updateFab();
        });
        updateFab();
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
        fab.setVisibility(browsing ? View.VISIBLE : View.GONE);
        fabBadge.setVisibility(ready ? View.VISIBLE : View.GONE);
        fab.setBackgroundResource(ready ? R.drawable.fab_bg_active : R.drawable.fab_bg_idle);
        if (ready) fabBadge.setText(readyCount > 9 ? "9+" : String.valueOf(readyCount));
    }

    private void enqueueDirect(String url, String userAgent, String contentDisposition,
                               String mimeType, long contentLength) {
        MediaItem item = new MediaItem(url);
        item.pageUrl = webView.getUrl();
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
                if (browsing && webView.canGoBack()) {
                    webView.goBack();
                } else if (browsing) {
                    blankCurrentTab();
                } else {
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
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
        String url = PENDING_URL.getAndSet(null);
        if (TextUtils.isEmpty(url) || webView == null) return;
        newTab();
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

        // A blank tab is the shortcut grid, and the grid is what its card should show. Nothing to
        // save besides the picture: there is no page and no history.
        if (!browsing || tab.isBlank()) {
            tab.previewPath = TabStore.capture(requireContext(), shortcutGrid, tab, true);
            return;
        }
        if (webView == null) return;

        Bundle state = new Bundle();
        webView.saveState(state);
        tab.state = state;
        tab.previewPath = TabStore.capture(requireContext(), webView, tab, true);
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
     * Throws away what was detected on the page being left.
     *
     * <p>Needed because the detector follows the WebView, not the tab. It starts over on its own
     * when the address changes site, so without this two tabs on the same site would pool their
     * findings: open a second Facebook tab and the sheet would offer videos from the first, and
     * the badge would count them.
     */
    private void resetDetection(String url) {
        registry.startPage(url == null ? "" : url);
        loadedUrl = null;
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
     * Brings a tab to the front.
     *
     * <p>History first, address second. Restoring the saved state puts the tab's back stack back
     * where it was and reloads the page it was on; only a tab with no saved state — one restored
     * from a previous run of the app — is loaded by address.
     */
    @Override
    public void openTab(Tab tab) {
        if (tab == null || tab.id.equals(currentTabId)) return;
        stashCurrentTab();
        currentTabId = tab.id;
        resetDetection(tab.url);

        if (tab.isBlank()) {
            webView.loadUrl("about:blank");
            showHome();
        } else if (tab.state != null) {
            showBrowser();
            webView.restoreState(tab.state);
        } else {
            load(tab.url);
        }
        updateTabCount();
        saveTabs();
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
        TabStore.deletePreview(tab);
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
            TabStore.deletePreview(tab);
            tab.url = "";
            tab.title = "";
            tab.state = null;
        }
        resetDetection("");
        if (webView != null) webView.loadUrl("about:blank");
        showHome();
        captureGridSoon();
        saveTabs();
    }

    /** A tab with no page: the shortcut grid, and nothing carried over from before. */
    @Override
    public void newTab() {
        stashCurrentTab();
        Tab tab = new Tab(TabStore.newId());
        tabs.add(tab);
        currentTabId = tab.id;
        resetDetection("");

        if (webView != null) webView.loadUrl("about:blank");
        showHome();
        captureGridSoon();
        updateTabCount();
        saveTabs();
    }

    @Override
    public void closeAllTabs() {
        for (Tab tab : tabs) TabStore.deletePreview(tab);
        tabs.clear();
        currentTabId = null;
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
     * <p>A restored tab is loaded from its address rather than from saved history — see
     * {@link Tab#state} for why the history is not kept across runs.
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
        if (open.isBlank()) {
            showHome();
        } else {
            load(open.url);
        }
        updateTabCount();
    }

    private void hideKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(
                requireContext(), InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        addressBar.clearFocus();
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.setWebChromeClient(null);
            webView.destroy();
        }
        super.onDestroyView();
    }
}
