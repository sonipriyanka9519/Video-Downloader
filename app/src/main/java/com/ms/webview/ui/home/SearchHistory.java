package com.ms.webview.ui.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the browser has been, newest first.
 *
 * <p>Kept so the search screen can offer somewhere the viewer has already been rather than an
 * empty box. A phone is a poor place to type an address twice.
 */
public final class SearchHistory {

    private static final String TAG = "History";
    private static final String PREFS = "search_history";

    /**
     * Two lists, because they answer different questions.
     *
     * <p>{@code KEY_ENTRIES} is every page visited — the history proper, which belongs on a screen
     * of its own. {@code KEY_QUERIES} is only what was typed into the address bar and searched
     * for, which is what a half-typed search should be offered against: a list of pages is a poor
     * answer to "what was I looking for", and a list of searches is a poor history.
     */
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_QUERIES = "queries";

    /**
     * How many visits are remembered. Long enough to cover a few days of use, short enough that
     * the list is read and written on every page load without anyone noticing.
     */
    private static final int MAX_ENTRIES = 60;

    /** One visit. */
    public static class Entry {
        public final String url;
        public final String title;
        /** When it was last visited, so the list can be broken up by day. */
        public final long visitedAt;

        public Entry(String url, String title, long visitedAt) {
            this.url = url;
            this.title = title;
            this.visitedAt = visitedAt;
        }

        /** What to show on the first line: the page's name, or its address when it has none. */
        public String label() {
            return TextUtils.isEmpty(title) ? url : title;
        }
    }

    private SearchHistory() {
    }

    public static List<Entry> all(Context context) {
        List<Entry> entries = new ArrayList<>();
        String saved = prefs(context).getString(KEY_ENTRIES, null);
        if (TextUtils.isEmpty(saved)) return entries;

        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String url = o.optString("url");
                if (TextUtils.isEmpty(url)) continue;
                // Entries written before visits were timestamped have none; they group under
                // whatever day zero falls on, which is honest — we do not know when they were.
                entries.add(new Entry(url, o.optString("title", ""), o.optLong("time", 0)));
            }
        } catch (Exception e) {
            Log.w(TAG, "history unreadable, starting fresh", e);
            return new ArrayList<>();
        }
        return entries;
    }

    /**
     * Records a visit, or moves it to the front if it has been visited before.
     *
     * <p>Moved rather than repeated: a list where the same page appears eleven times is a list
     * with ten wasted rows in it. The title is refreshed on the way, since a page often reports
     * its name a moment after its address.
     */
    public static void record(Context context, String url, String title) {
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) return;

        List<Entry> entries = all(context);
        String keptTitle = title;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!url.equals(entries.get(i).url)) continue;
            // Keep the name we had if this visit arrived without one.
            if (TextUtils.isEmpty(keptTitle)) keptTitle = entries.get(i).title;
            entries.remove(i);
        }

        entries.add(0, new Entry(url, keptTitle == null ? "" : keptTitle,
                System.currentTimeMillis()));
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        write(context, entries);
    }

    public static void remove(Context context, String url) {
        List<Entry> entries = all(context);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (url.equals(entries.get(i).url)) entries.remove(i);
        }
        write(context, entries);
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply();
    }

    // ------------------------------------------------------------------- searches

    /** What has been typed into the address bar and searched for, newest first. */
    public static List<String> queries(Context context) {
        List<String> queries = new ArrayList<>();
        String saved = prefs(context).getString(KEY_QUERIES, null);
        if (TextUtils.isEmpty(saved)) return queries;

        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                String query = array.optString(i);
                if (!TextUtils.isEmpty(query)) queries.add(query);
            }
        } catch (Exception e) {
            Log.w(TAG, "searches unreadable, starting fresh", e);
            return new ArrayList<>();
        }
        return queries;
    }

    /**
     * Records a search, or moves it to the front if it has been searched before.
     *
     * <p>Only what the viewer typed. An address they pasted is not something they were looking
     * for — it is somewhere they already knew about — and offering it back to them as a search
     * fills the list with things nobody would ever type.
     */
    public static void recordQuery(Context context, String text) {
        if (TextUtils.isEmpty(text)) return;
        String query = text.trim();
        if (query.isEmpty()) return;

        List<String> queries = queries(context);
        for (int i = queries.size() - 1; i >= 0; i--) {
            if (query.equalsIgnoreCase(queries.get(i))) queries.remove(i);
        }
        queries.add(0, query);
        while (queries.size() > MAX_ENTRIES) queries.remove(queries.size() - 1);

        JSONArray array = new JSONArray();
        for (String q : queries) array.put(q);
        prefs(context).edit().putString(KEY_QUERIES, array.toString()).apply();
    }

    public static void removeQuery(Context context, String text) {
        List<String> queries = queries(context);
        for (int i = queries.size() - 1; i >= 0; i--) {
            if (text.equalsIgnoreCase(queries.get(i))) queries.remove(i);
        }
        JSONArray array = new JSONArray();
        for (String q : queries) array.put(q);
        prefs(context).edit().putString(KEY_QUERIES, array.toString()).apply();
    }

    private static void write(Context context, List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            try {
                JSONObject o = new JSONObject();
                o.put("url", entry.url);
                o.put("title", entry.title);
                o.put("time", entry.visitedAt);
                array.put(o);
            } catch (Exception ignored) {
                // One unwritable row is not worth losing the rest of the list over.
            }
        }
        prefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
