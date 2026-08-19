package com.ms.webview.ui.tabs;

import android.os.Bundle;
import android.text.TextUtils;
import android.webkit.WebView;

import androidx.annotation.Nullable;

/**
 * One browser tab.
 *
 * <p>A tab with no address is a new tab: it shows the shortcut grid and has nothing to restore.
 * That is a state, not an absence — closing the last tab leaves one of these rather than leaving
 * the browser with nowhere to be.
 */
public class Tab {

    /** Stable across a rename or a navigation, so the switcher can track which card is which. */
    public final String id;

    /** Where the tab is, or empty for a new tab showing the grid. */
    public String url = "";
    /** The page's own title, for the card's first line. */
    public String title = "";
    /** A JPEG of the page as it was last seen, in the cache directory. Empty until captured. */
    public String previewPath = "";

    /**
     * A private tab — screen 05, and the privacy invariants in CLAUDE.md.
     *
     * <p>Not merely a label. A private tab writes nothing that outlives it: no history entry, no
     * search suggestion, no page title, no preview on disk, and no row in the saved tab list. It
     * exists for as long as it is open and leaves nothing behind when it closes.
     *
     * <p>Deliberately not persisted, and the field is transient for that reason as much as any
     * other — a private tab that survived a restart would be a private tab written to storage.
     */
    public transient boolean incognito;

    /**
     * The WebView's back/forward history, held only for as long as the app is running.
     *
     * <p>Not written to disk with the rest, and deliberately: a saved history is a bundle of
     * private browsing state, it is large, and restoring one for a page that has since changed is
     * worse than loading the page fresh. Within a session it means switching tabs does not cost
     * you the ability to go back.
     */
    @Nullable
    public transient Bundle state;

    /**
     * The tab's own browser, alive for as long as the tab is worth keeping loaded.
     *
     * <p>This is what makes returning to a tab instant. A page's real state — where it is
     * scrolled, what its scripts have built, a video's position — lives in the browser instance
     * and cannot be written down: {@link #state} records the history, and restoring it re-fetches
     * the page rather than resuming it. Keeping the instance keeps the page.
     *
     * <p>Null for a tab showing the grid, and null for one whose browser has been reclaimed to
     * save memory — see {@code LIVE_TAB_LIMIT}. Either way the tab is still a tab; it simply has
     * to load its address again when next opened.
     */
    @Nullable
    public transient WebView view;

    /** When this tab was last in front, for deciding whose browser to reclaim first. */
    public transient long lastShownAt;

    public Tab(String id) {
        this.id = id;
    }

    public boolean isBlank() {
        return TextUtils.isEmpty(url);
    }
}
