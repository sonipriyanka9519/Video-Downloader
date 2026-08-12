package com.ms.webview.ui.tabs;

import android.os.Bundle;
import android.text.TextUtils;

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
     * The WebView's back/forward history, held only for as long as the app is running.
     *
     * <p>Not written to disk with the rest, and deliberately: a saved history is a bundle of
     * private browsing state, it is large, and restoring one for a page that has since changed is
     * worse than loading the page fresh. Within a session it means switching tabs does not cost
     * you the ability to go back.
     */
    @Nullable
    public transient Bundle state;

    public Tab(String id) {
        this.id = id;
    }

    public boolean isBlank() {
        return TextUtils.isEmpty(url);
    }
}
