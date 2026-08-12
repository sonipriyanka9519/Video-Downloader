package com.ms.webview.ui.home;

import androidx.annotation.DrawableRes;

/** One tile on the home grid. */
public final class Shortcut {

    /** Stable key, so the saved arrangement survives a change of label or URL. */
    public final String id;
    public final String label;
    public final String url;

    /**
     * The site's own mark, bundled. Fetching favicons meant an empty grid until the network
     * answered, and a permanently empty one for anything that blocked the request.
     */
    @DrawableRes
    public final int icon;

    Shortcut(String id, String label, String url, @DrawableRes int icon) {
        this.id = id;
        this.label = label;
        this.url = url;
        this.icon = icon;
    }
}
