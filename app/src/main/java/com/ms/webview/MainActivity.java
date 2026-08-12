package com.ms.webview;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.URLUtil;

import java.util.ArrayList;
import java.util.List;

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

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.ms.webview.ui.BrowserFragment;
import com.ms.webview.ui.MainPagerAdapter;

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

    private ViewPager2 pager;
    private BottomNavigationView bottomNav;
    private View navDivider;

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
            setNavVisible(ime.bottom == 0);
            return insets;
        });

        App.get().repository().reconcileOnStartup();

        setUpTabs();
        setUpPermissions();
        applyRequestedTab(getIntent());
        applySharedLink(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        applyRequestedTab(intent);
        applySharedLink(intent);
    }

    private void applyRequestedTab(@Nullable Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false)) return;
        // No animation: this is where the user asked to arrive, not somewhere they slid to.
        pager.setCurrentItem(MainPagerAdapter.PAGE_DOWNLOADS, false);
    }

    /**
     * A link shared or opened from another app.
     *
     * <p>Left for the browser to pick up rather than pushed into it. The fragment may not exist
     * yet — on a cold start this runs before the pager has created it — and a link handed to a
     * fragment that is not there is a link lost. Parking it means the browser collects it
     * whenever it is ready, whether that is in a moment or immediately.
     */
    private void applySharedLink(@Nullable Intent intent) {
        String url = sharedUrlOf(intent);
        if (url == null) return;

        BrowserFragment.openWhenReady(url);
        pager.setCurrentItem(MainPagerAdapter.PAGE_HOME, false);
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
     * The tab bar steps aside for the keyboard. Typing an address is a full-attention task and
     * the bar is not reachable under a keyboard anyway, so leaving it there only costs two rows
     * of the page.
     */
    private void setNavVisible(boolean visible) {
        int state = visible ? View.VISIBLE : View.GONE;
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

        bottomNav.setOnItemSelectedListener(item -> {
            pager.setCurrentItem(item.getItemId() == R.id.navDownloads
                    ? MainPagerAdapter.PAGE_DOWNLOADS
                    : MainPagerAdapter.PAGE_HOME, true);
            return true;
        });

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                // Set on the menu rather than through the listener, so a swipe does not loop
                // back into setCurrentItem mid-animation.
                bottomNav.getMenu()
                        .findItem(position == MainPagerAdapter.PAGE_DOWNLOADS
                                ? R.id.navDownloads : R.id.navHome)
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
}
