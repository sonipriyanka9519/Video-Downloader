package com.ms.webview;

import android.Manifest;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.URLUtil;

import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.push.PushLink;
import com.ms.webview.ads.Interstitials;
import com.ms.webview.ui.BrowserFragment;
import com.ms.webview.ui.MainPagerAdapter;
import com.ms.webview.ui.Snacks;

/**
 * The shell: two tabs, and the window-level concerns that belong to neither of them.
 *
 * <p>Everything that used to live here — the WebView, detection, the downloads list — has moved
 * into {@link com.ms.webview.ui.BrowserFragment} and
 * {@link com.ms.webview.ui.DownloadsFragment}, so that switching tabs keeps the page loaded and
 * the detector running rather than tearing an activity down.
 */
public class MainActivity extends AppCompatActivity {

    /** Set by the download notification, so tapping it lands on the downloads list. */
    public static final String EXTRA_OPEN_DOWNLOADS = "open_downloads";

    /**
     * The launcher shortcuts — screen 18, panel E. Values must match res/xml/shortcuts.xml,
     * which spells them out because a shortcut's intent is declared in XML rather than built here.
     */
    public static final String EXTRA_OPEN_SEARCH = "open_search";
    public static final String EXTRA_OPEN_PRIVATE_TAB = "open_private_tab";


    private ViewPager2 pager;
    private BottomNavigationView bottomNav;
    /** The two reasons the tab bar steps aside, held apart so neither undoes the other. */
    private boolean keyboardUp;
    private boolean selecting;
    private View navDivider;

    /**
     * Whether the browser is showing a private tab, remembered rather than asked for.
     *
     * <p>The window is re-initialised behind our backs more often than it looks: EdgeToEdge sets
     * the status bar in onCreate, and a theme change runs onCreate again. Keeping the answer here
     * means onResume can put the bar back without having to go and ask the browser, which may not
     * be the tab in front and may not even exist yet.
     */
    private boolean privateChrome;

    private ActivityResultLauncher<String[]> permissions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // The opening now belongs to SplashActivity; this is reached already branded.
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        pager = findViewById(R.id.mainPager);
        bottomNav = findViewById(R.id.bottomNav);
        navDivider = findViewById(R.id.navDivider);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            // Bottom is deliberately zero. BottomNavigationView pads itself for the gesture
            // area, and adding the same inset here as well is what left a band of empty
            // surface between the bar and the pill.
            v.setPadding(bars.left, bars.top, bars.right, 0);

            // Neither is the keyboard. Padding for it pushed the page up by the keyboard's own
            // height on top of the space the window had already surrendered, which is what
            // squashed the tab into a strip while typing.
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            keyboardUp = ime.bottom > 0;
            applyNavVisibility();
            return insets;
        });

        App.get().repository().reconcileOnStartup();

        setUpTabs();
        setUpDownloadBadge();
        setUpPermissions();
        applyRequestedTab(getIntent());
        applyIncomingLink(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Anything the player owed on the way out — see Interstitials.queueForNextScreen.
        Interstitials.showIfQueued(this);

        // The status bar is set on the window, not on a view, so nothing restores it for us. Put
        // it back to whatever the browser last asked for - after a theme change, after returning
        // from the player, after anything that ran onCreate again.
        setPrivateChrome(privateChrome);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyRequestedTab(intent);
        applyIncomingLink(intent);
    }

    /**
     * Whether the copied link has to wait its turn.
     *
     * <p>Some ways in already carry a purpose: a link shared from another app, a link this app was
     * chosen to open, a pushed video, and a download notification. Each of those is the viewer
     * telling us what they came for, and a dialog about the clipboard on top of it is an
     * interruption — the more so because a shared link is usually the very thing on the clipboard.
     *
     * <p>The pushed video needs no case of its own here: {@link #sharedUrlOf} finds it, because
     * from this point on a link is a link however it arrived.
     *
     * <p>Deferred, never cancelled. Pressing Home is the viewer finished with what they arrived
     * with, and the offer is made then. Set on every entry rather than only on those, so an
     * ordinary launch afterwards clears a deferral left over from the last one.
     */
    private void applyClipboardPriority(@Nullable Intent intent, @Nullable String link) {
        BrowserFragment.deferClipboardPrompt(link != null
                || (intent != null && intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)));
    }

    /**
     * Raises the default-browser question every time the app comes to the foreground.
     *
     * <p>onStart rather than onCreate, because with a single task the activity is created once and
     * then simply resumed — an app opened again from the launcher an hour later would never reach
     * onCreate, and the offer would be made once in the lifetime of the process.
     *
     * <p>This fires on returns from our own screens too. The browser is what decides whether to
     * act on it, and it holds a quiet period for exactly that reason: coming back from the search
     * screen seconds later is not opening the app.
     */
    @Override
    protected void onStart() {
        super.onStart();
        // Nothing stands in front of this screen. The lock guards the private folder and the
        // videos put into it — not the app, which has to open on the tab the viewer left it on
        // for the same reason a gallery does. See AppLock.
        //
        // The default-browser offer used to be armed here, which meant every return from another
        // app was a fresh chance to be asked. It is armed once per launch now — see App.onCreate.
    }

    /**
     * Which page a tab shows, and which tab a page belongs to.
     *
     * <p>Two methods rather than one map, and both exhaustive: the pager and the bar each drive the
     * other, so a page added to one and forgotten in the other leaves a tab that selects nothing.
     */
    private static int pageFor(int navItemId) {
        if (navItemId == R.id.navDownloads) return MainPagerAdapter.PAGE_DOWNLOADS;
        if (navItemId == R.id.navSettings) return MainPagerAdapter.PAGE_SETTINGS;
        return MainPagerAdapter.PAGE_HOME;
    }

    private static int navItemFor(int page) {
        if (page == MainPagerAdapter.PAGE_DOWNLOADS) return R.id.navDownloads;
        if (page == MainPagerAdapter.PAGE_SETTINGS) return R.id.navSettings;
        return R.id.navHome;
    }

    private void applyRequestedTab(@Nullable Intent intent) {
        if (intent == null) return;

        // Parked and then switched to, because on a cold start the fragment does not exist yet -
        // the same reasoning as applyIncomingLink, and the browser collects them together.
        boolean search = intent.getBooleanExtra(EXTRA_OPEN_SEARCH, false);
        boolean privateTab = intent.getBooleanExtra(EXTRA_OPEN_PRIVATE_TAB, false);
        if (search || privateTab) {
            if (privateTab) BrowserFragment.openPrivateTabWhenReady();
            if (search) BrowserFragment.openSearchWhenReady();
            pager.setCurrentItem(MainPagerAdapter.PAGE_HOME, false);
            return;
        }

        if (!intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) return;
        // No animation: this is where the user asked to arrive, not somewhere they slid to.
        pager.setCurrentItem(MainPagerAdapter.PAGE_DOWNLOADS, false);
    }

    /**
     * A link shared or opened from another app, or pushed to this one.
     *
     * <p>Left for the browser to pick up rather than pushed into it. The fragment may not exist
     * yet — on a cold start this runs before the pager has created it — and a link handed to a
     * fragment that is not there is a link lost. Parking it means the browser collects it
     * whenever it is ready, whether that is in a moment or immediately.
     *
     * <p>One pass, and the intent is read once. The clipboard offer has to be told about the same
     * link, and reading twice would mean reading it after it has been taken — which is exactly
     * when the answer changes.
     */
    private void applyIncomingLink(@Nullable Intent intent) {
        String url = sharedUrlOf(intent);
        applyClipboardPriority(intent, url);
        if (url == null) return;

        BrowserFragment.openWhenReady(url);
        pager.setCurrentItem(MainPagerAdapter.PAGE_HOME, false);

        // Taken, so it cannot be taken again. An intent outlives the moment it arrived in: the
        // one a notification launched us with is still the activity's intent afterwards, and
        // without this the video would open in a second new tab every time the activity was
        // rebuilt from it.
        PushLink.consume(intent);
    }

    /**
     * The address in an incoming intent, whether it arrived as a link to open or as shared text.
     *
     * <p>Shared text is rarely only a link — a share from a video app is usually a title, a
     * blank line and then the address — so the first thing in it that looks like one is taken.
     */
    @Nullable
    private static String sharedUrlOf(@Nullable Intent intent) {
        if (intent == null) return null;

        // A pushed video, whether this app built the notification or Firebase displayed it and
        // copied the payload into the launch intent. Read first because such an intent carries no
        // action of its own — it is simply a launch with something in its pocket.
        String pushed = PushLink.from(intent.getExtras());
        if (pushed != null) return pushed;

        String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            String url = intent.getData().toString();
            return URLUtil.isNetworkUrl(url) ? url : null;
        }

        if (!Intent.ACTION_SEND.equals(action)) return null;

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null) return null;
        for (String word : text.split("\\s+")) {
            if (URLUtil.isNetworkUrl(word)) return word;
        }
        return null;
    }

    /**
     * The library takes the bottom of the window while a selection is being made — screen 07.
     *
     * <p>Its action bar goes where the tab bar is, so Delete never ends up sitting beside Home.
     * Kept as a separate flag rather than a direct call, or the next keyboard inset would put
     * the tab bar back underneath the actions.
     */
    public void setNavHiddenForSelection(boolean hidden) {
        selecting = hidden;
        applyNavVisibility();
    }

    /**
     * The tab bar steps aside for the keyboard, and for a selection.
     *
     * <p>Typing an address is a full-attention task and the bar is not reachable under a
     * keyboard anyway, so leaving it there only costs two rows of the page.
     */
    private void applyNavVisibility() {
        int state = keyboardUp || selecting ? View.GONE : View.VISIBLE;
        if (bottomNav.getVisibility() == state) return;
        bottomNav.setVisibility(state);
        navDivider.setVisibility(state);
    }

    private void setUpTabs() {
        pager.setAdapter(new MainPagerAdapter(this));
        // Both tabs are cheap to keep alive and expensive to rebuild: the browser would lose its
        // page and every video found on it.
        pager.setOffscreenPageLimit(1);
        // Tabs change on a tap only. A web page is full of things that scroll sideways, and a
        // pager underneath it would keep taking gestures meant for the page.
        pager.setUserInputEnabled(false);

        // Set once here so the field is available to showDownloads below.
        bottomNav.setOnItemSelectedListener(item -> {
            pager.setCurrentItem(pageFor(item.getItemId()), true);
            return true;
        });

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // Set on the menu rather than through the listener, so a swipe does not loop
                // back into setCurrentItem mid-animation.
                bottomNav.getMenu()
                        .findItem(navItemFor(position))
                        .setChecked(true);
            }
        });

        // From Downloads, Back returns to Home rather than leaving the app. The browser tab
        // registers its own callback later, which takes precedence while it is the one on show.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (pager.getCurrentItem() != MainPagerAdapter.PAGE_HOME) {
                    pager.setCurrentItem(MainPagerAdapter.PAGE_HOME, true);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void setUpPermissions() {
        permissions = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), granted -> {
                    // Downloads run either way. Without notifications the progress is hidden;
                    // without media access the Finished list only shows this install's own
                    // videos, so re-read it as soon as the answer comes back.
                    App.get().repository().refreshLibrary();
                });

        List<String> wanted = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(wanted, Manifest.permission.POST_NOTIFICATIONS);
            // Reading the library is what lets a reinstall find videos the previous install
            // saved: MediaStore stops treating us as their owner the moment the app is replaced.
            add(wanted, Manifest.permission.READ_MEDIA_VIDEO);
        } else {
            // Its predecessor, for the same reason: scoped storage shows an app only the files
            // it owns, and a reinstalled app owns none of them.
            add(wanted, Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (!wanted.isEmpty()) permissions.launch(wanted.toArray(new String[0]));
    }

    private void add(List<String> wanted, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            wanted.add(permission);
        }
    }

    /**
     * A count on the Downloads tab while anything is arriving.
     *
     * <p>The badge is the whole of how a transfer announces itself once the sheet has closed —
     * the design is explicit that detection and downloading never raise a toast or open anything
     * — so it has to be live rather than set when the screen is opened.
     *
     * <p>Fed from the same repository the library reads, so the number on the tab and the rows
     * under DOWNLOADING can never disagree.
     */
    private void setUpDownloadBadge() {
        App.get().repository().observeAll().observe(this, downloads -> {
            int active = 0;
            if (downloads != null) {
                for (DownloadEntity d : downloads) {
                    if (d.status == DownloadStatus.RUNNING
                            || d.status == DownloadStatus.QUEUED
                            || d.status == DownloadStatus.PUBLISHING) {
                        active++;
                    }
                }
            }

            BadgeDrawable badge = bottomNav.getOrCreateBadge(R.id.navDownloads);
            badge.setVisible(active > 0);
            badge.setNumber(active);
            badge.setBackgroundColor(ContextCompat.getColor(this, R.color.ds_accent));
            badge.setBadgeTextColor(ContextCompat.getColor(this, R.color.ds_on_accent));
            // A pill rather than the circle a one-digit badge defaults to. One download and two
            // downloads should be the same shape — a badge that changes form as the number grows
            // reads as two different things rather than one count.
            badge.setHorizontalPadding(getResources()
                    .getDimensionPixelSize(R.dimen.ds_badge_padding));
        });
    }

    /**
     * Switches to the Downloads tab.
     *
     * <p>Set through the nav rather than the pager, so the selected item and the page agree —
     * the listener above is what moves the pager, and driving the pager directly would leave the
     * nav showing Home while Downloads is on screen.
     */
    public void showDownloads() {
        if (bottomNav != null) bottomNav.setSelectedItemId(R.id.navDownloads);
    }

    /**
     * A word about a download that has just started, above the tab bar.
     *
     * <p>A snackbar rather than a toast, and it belongs to the activity rather than to whatever
     * raised it: the sheet that starts a download closes on the same tap, and a toast fired from
     * a dying fragment has nowhere to sit. Anchored to the tab bar so it never covers it.
     *
     * <p>Five seconds. Long enough to notice while looking at the page rather than the message,
     * and short enough not to sit over the first row of a list somebody is already scrolling.
     *
     * <p>Coloured by hand rather than by the theme. The snackbar is built against the activity,
     * which is still on the MVP palette, so leaving it to inherit would give it the old surface
     * in light and an unreadably dark one in night — the ds_snackbar_* tokens have both.
     */
    public void showDownloadNotice(CharSequence text) {
        View root = findViewById(R.id.main);
        if (root == null || text == null) return;

        // Anchored to the tab bar, unless it has stepped aside — anchoring to something hidden
        // would leave the message floating in the middle of the screen.
        Snackbar bar = Snacks.make(root, text, Snacks.NOTICE_MS, bottomNav);

        // Somewhere to go, since the message is about a thing now happening elsewhere. The
        // snackbar dismisses itself on the tap, so this only has to change tab.
        bar.setAction(R.string.view, v -> showDownloads());

        Snacks.withTimer(bar, Snacks.NOTICE_MS);
        bar.show();
    }

    /**
     * Carries the private tab's grey up into the status bar.
     *
     * <p>The browser column tints itself, but the strip behind the clock belongs to this activity —
     * it is padding on the root, and the root is the only view that owns the status-bar inset. Left
     * alone it stayed the ordinary surface, so a private tab was grey everywhere except the one
     * band across the very top, which reads as a rendering fault rather than as a deliberate mark.
     *
     * <p>Only the browser asks for this, and only while it is the tab in front: the downloads list
     * and settings are not private, and a grey top over them would say they were. See
     * BrowserFragment.showPrivateMark, which sets it, and its onPause, which takes it back.
     */
    public void setPrivateChrome(boolean secret) {
        privateChrome = secret;
        View root = findViewById(R.id.main);
        if (root == null) return;
        // Only the status strip is reached by this - the root is otherwise covered by the pager -
        // and it takes the same grey as the address bar directly beneath it, so the two read as one
        // band rather than as two greys meeting in a seam.
        // ds_bg, the same colour the page below the toolbar is painted. The strip, the toolbar and
        // the page are one continuous surface in the design; toolbar_surface is a shade lighter
        // than ds_bg, so using it drew a visible band across the top of every screen.
        root.setBackgroundResource(secret ? R.color.ds_private_bg : R.color.ds_bg);

        // The tab bar is deliberately left alone, and so is the gesture bar below it. It belongs to
        // the app rather than to the tab - Home, Downloads and Settings are reached from it and none
        // of them is private - so tinting it said the whole app had changed state when only the page
        // above it had.

        // The status strip, which neither of the above can reach.
        //
        // Measured rather than reasoned about, because the first two attempts at this were wrong.
        // On this platform the app content occupies y 80..1504 of a 1600px window: the strip behind
        // the clock is outside the content view entirely, so no background set on R.id.main can
        // paint it, and the pixel there reads back as colorBackground - the window background.
        //
        // setStatusBarColor is the older lever and still the working one below API 35. From 35 it
        // is a documented no-op, which is why setting it alone left the strip stubbornly unchanged
        // while the address bar directly beneath it turned grey. Both are set, so whichever the
        // platform honours gives the same answer.
        int bar = ContextCompat.getColor(this,
                secret ? R.color.ds_private_bg : R.color.ds_bg);
        getWindow().setStatusBarColor(bar);
        getWindow().setBackgroundDrawable(new ColorDrawable(bar));
    }

    /**
     * Opens a page in the browser tab — the Source link in Properties, screen 07.
     *
     * <p>The same route an incoming intent takes: queued for the browser and then switched to,
     * because the fragment may not have a WebView yet when the tap happens.
     */
    public void openInBrowser(@Nullable String url) {
        if (url == null || url.isEmpty()) return;
        BrowserFragment.openWhenReady(url);
        if (bottomNav != null) bottomNav.setSelectedItemId(R.id.navHome);
    }
}
