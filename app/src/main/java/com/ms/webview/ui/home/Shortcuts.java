package com.ms.webview.ui.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.ms.webview.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The home grid's contents.
 *
 * <p>The catalogue is closed: it is exactly the set of sites the app has an extractor for. There
 * is no way to add anything else, because a tile for a site we cannot read a video from would be
 * a promise the app does not keep — the address bar is still there for one-off browsing.
 *
 * <p>Only a handful show by default. The rest are one tap away behind Add, which keeps the grid
 * to the platforms people actually open without hiding what the app supports.
 */
public final class Shortcuts {

    private static final String PREFS = "shortcuts";
    private static final String KEY_SHOWN = "shown";
    private static final String SEPARATOR = ",";

    /** Insertion order is grid order. */
    private static final Map<String, Shortcut> CATALOG = new LinkedHashMap<>();

    /** What a fresh install shows. */
    private static final String[] DEFAULTS = {
            "instagram", "facebook", "x", "pinterest", "reddit", "threads", "dailymotion",
    };

    static {
        put("instagram", "Instagram", "https://www.instagram.com", R.drawable.brand_instagram);
        put("facebook", "Facebook", "https://www.facebook.com", R.drawable.brand_facebook);
        put("x", "X", "https://x.com", R.drawable.brand_x);
        put("pinterest", "Pinterest", "https://www.pinterest.com", R.drawable.brand_pinterest);
        put("reddit", "Reddit", "https://www.reddit.com", R.drawable.brand_reddit);
        put("threads", "Threads", "https://www.threads.net", R.drawable.brand_threads);
        put("dailymotion", "Dailymotion", "https://www.dailymotion.com",
                R.drawable.brand_dailymotion);
        put("tiktok", "TikTok", "https://www.tiktok.com", R.drawable.brand_tiktok);
        put("twitch", "Twitch", "https://www.twitch.tv", R.drawable.brand_twitch);
        put("vimeo", "Vimeo", "https://vimeo.com", R.drawable.brand_vimeo);
        put("linkedin", "LinkedIn", "https://www.linkedin.com", R.drawable.brand_linkedin);
        put("tumblr", "Tumblr", "https://www.tumblr.com", R.drawable.brand_tumblr);
        put("snapchat", "Snapchat", "https://www.snapchat.com", R.drawable.brand_snapchat);
        put("sharechat", "ShareChat", "https://sharechat.com", R.drawable.brand_sharechat);
        put("vk", "VK", "https://vk.com", R.drawable.brand_vk);
        put("bilibili", "Bilibili", "https://www.bilibili.com", R.drawable.brand_bilibili);
        put("imdb", "IMDb", "https://www.imdb.com", R.drawable.brand_imdb);
        put("kwai", "Kwai", "https://www.kwai.com", R.drawable.brand_kwai);
        put("likee", "Likee", "https://likee.video", R.drawable.brand_likee);
        put("fandom", "Fandom", "https://www.fandom.com", R.drawable.brand_fandom);
        put("flickr", "Flickr", "https://www.flickr.com", R.drawable.brand_flickr);
        put("google", "Google", "https://www.google.com", R.drawable.brand_google);
    }

    private Shortcuts() {
    }

    private static void put(String id, String label, String url, int icon) {
        CATALOG.put(id, new Shortcut(id, label, url, icon));
    }

    /** The tiles on the grid, in the order the user arranged them. */
    public static List<Shortcut> shown(Context context) {
        List<Shortcut> list = new ArrayList<>();
        for (String id : shownIds(context)) {
            Shortcut shortcut = CATALOG.get(id);
            // Skips silently: an id saved by an older build whose site has since gone.
            if (shortcut != null) list.add(shortcut);
        }
        return list;
    }

    /**
     * The mark for the site an address belongs to, or 0 when it is not one we carry.
     *
     * <p>The catalogue already holds a proper brand icon for every site the app can read a video
     * from, so a link to one of them can be shown with its own mark rather than a generic globe.
     * Matched on the host and on nothing else: a catalogue entry's address is a home page, and a
     * link is almost never the home page.
     *
     * <p>Compared from the right-hand end, so {@code m.facebook.com} and {@code www.facebook.com}
     * both find Facebook while a host that merely ends in the same letters does not.
     */
    public static int iconForUrl(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) return 0;

        for (Shortcut shortcut : CATALOG.values()) {
            String known = hostOf(shortcut.url);
            if (known.isEmpty()) continue;
            // "facebook.com" itself, or anything under it.
            if (host.equals(known) || host.endsWith("." + known)) return shortcut.icon;

            // Catalogue addresses are written with www; the site is the part after it.
            String bare = known.startsWith("www.") ? known.substring(4) : known;
            if (host.equals(bare) || host.endsWith("." + bare)) return shortcut.icon;
        }
        return 0;
    }

    private static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            String host = android.net.Uri.parse(url).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    /** Everything the app supports that is not currently on the grid. */
    public static List<Shortcut> hidden(Context context) {
        List<String> shown = shownIds(context);
        List<Shortcut> list = new ArrayList<>();
        for (Map.Entry<String, Shortcut> entry : CATALOG.entrySet()) {
            if (!shown.contains(entry.getKey())) list.add(entry.getValue());
        }
        return list;
    }

    public static void add(Context context, Shortcut shortcut) {
        List<String> shown = shownIds(context);
        if (!CATALOG.containsKey(shortcut.id) || shown.contains(shortcut.id)) return;
        shown.add(shortcut.id);
        write(context, shown);
    }

    public static void remove(Context context, Shortcut shortcut) {
        List<String> shown = shownIds(context);
        if (shown.remove(shortcut.id)) write(context, shown);
    }

    private static List<String> shownIds(Context context) {
        String saved = prefs(context).getString(KEY_SHOWN, null);
        if (saved == null) {
            List<String> defaults = new ArrayList<>();
            for (String id : DEFAULTS) defaults.add(id);
            return defaults;
        }
        // An empty string is a grid the user emptied, which is different from never having
        // touched it — split() would hand back one blank entry, so it is handled here.
        List<String> ids = new ArrayList<>();
        if (TextUtils.isEmpty(saved)) return ids;
        for (String id : saved.split(SEPARATOR)) {
            if (!TextUtils.isEmpty(id)) ids.add(id);
        }
        return ids;
    }

    private static void write(Context context, List<String> ids) {
        prefs(context).edit()
                .putString(KEY_SHOWN, TextUtils.join(SEPARATOR, ids))
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
