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

    /** Screen 12's retention answer, asked once inside the first Clear all. */
    private static final String KEY_AUTO_CLEAR = "auto_clear";
    private static final String KEY_RETENTION_ASKED = "retention_asked";

    /**
     * How many visits are remembered.
     *
     * <p>Raised from 60 when history got a screen of its own: a list somebody scrolls through
     * looking for a page from last week is worth more than a list of the last hour. Still a cap
     * rather than no cap, because this is read and rewritten on every page load — retention is
     * what is meant to bound it, and this is only the backstop.
     */
    private static final int MAX_ENTRIES = 300;

    /** Typed searches, which are a suggestion list and stay short. */
    private static final int MAX_QUERIES = 60;

    /** What "older than 30 days" means, in milliseconds. */
    private static final long RETENTION_MS = 30L * 24 * 60 * 60 * 1000;

    /** One visit. */
    public static class Entry {
        public final String url;
        public final String title;
        /** When it was last visited, so the list can be broken up by day. */
        public final long visitedAt;
        /**
         * How many times this page has been visited — the {@code ×4} chip on screen 12.
         *
         * <p>Never two rows for one address: a repeat visit moves the row to the front and raises
         * this instead. The design describes that as consecutive visits collapsing, and with one
         * row per address the result is the same thing said once — the count is every visit rather
         * than only a run of them, which is the more useful of the two answers anyway.
         */
        public final int visits;

        public Entry(String url, String title, long visitedAt) {
            this(url, title, visitedAt, 1);
        }

        public Entry(String url, String title, long visitedAt, int visits) {
            this.url = url;
            this.title = title;
            this.visitedAt = visitedAt;
            this.visits = Math.max(1, visits);
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
                // Same for the count: a row written before counting existed has been there once
                // as far as anyone can tell.
                entries.add(new Entry(url, o.optString("title", ""), o.optLong("time", 0),
                        o.optInt("n", 1)));
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
        int visits = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (!url.equals(entries.get(i).url)) continue;
            // Keep the name we had if this visit arrived without one.
            if (TextUtils.isEmpty(keptTitle)) keptTitle = entries.get(i).title;
            // And carry the count forward — this is the same page being visited again, which is
            // what the ×N chip on screen 12 is counting.
            visits = Math.max(visits, entries.get(i).visits);
            entries.remove(i);
        }

        entries.add(0, new Entry(url, keptTitle == null ? "" : keptTitle,
                System.currentTimeMillis(), visits + 1));
        // Old visits go here rather than on a timer: this is the one moment the list is already
        // being rewritten, so enforcing retention costs nothing extra.
        if (autoClearOld(context)) dropOlderThan(entries, System.currentTimeMillis() - RETENTION_MS);
        while (entries.size() > MAX_ENTRIES) entries.remove(entries.size() - 1);
        write(context, entries);
    }

    /** How many pages are remembered — the number the Clear all confirm names. */
    public static int count(Context context) {
        return all(context).size();
    }

    // ------------------------------------------------------------------- retention

    /**
     * Whether anything older than 30 days is dropped as it ages out — screen 12's one decision.
     *
     * <p>Off by default: history keeps everything until somebody says otherwise. The question is
     * asked once, pre-ticked, inside the first Clear all, and the answer then lives in Settings.
     */
    public static boolean autoClearOld(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_CLEAR, false);
    }

    public static void setAutoClearOld(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_AUTO_CLEAR, value).apply();
        // Applied at once rather than at the next page load: a switch that will do something
        // eventually is indistinguishable from one that did nothing.
        if (value) pruneOld(context);
    }

    /** Whether the retention question has been put yet, so it is only ever asked once. */
    public static boolean retentionAsked(Context context) {
        return prefs(context).getBoolean(KEY_RETENTION_ASKED, false);
    }

    public static void markRetentionAsked(Context context) {
        prefs(context).edit().putBoolean(KEY_RETENTION_ASKED, true).apply();
    }

    /** Drops anything past the retention window, when the viewer asked for that. */
    public static void pruneOld(Context context) {
        if (!autoClearOld(context)) return;

        List<Entry> entries = all(context);
        int before = entries.size();
        dropOlderThan(entries, System.currentTimeMillis() - RETENTION_MS);
        // Only written when something actually went, so opening the sheet is not a write.
        if (entries.size() != before) write(context, entries);
    }

    private static void dropOlderThan(List<Entry> entries, long cutoff) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            // A visit with no timestamp is left alone. It is old, but "we do not know when" is not
            // grounds for deleting somebody's history.
            long at = entries.get(i).visitedAt;
            if (at > 0 && at < cutoff) entries.remove(i);
        }
    }

    public static void remove(Context context, String url) {
        List<Entry> entries = all(context);
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (url.equals(entries.get(i).url)) entries.remove(i);
        }
        write(context, entries);
    }

    /**
     * Forgets every visit. Typed searches are a separate list and are left alone - see
     * {@link #clearQueries}, and the note on the keys for why the two are kept apart.
     *
     * <p>This is what screen 12's Clear all means: that screen shows visits, so that is what it
     * clears. A caller that means "forget everything" calls both.
     */
    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_ENTRIES).apply();
    }

    /**
     * Forgets what was typed and searched for.
     *
     * <p>This had no caller and no way to be reached, which is why Clear recent searches cleared
     * everything except the recent searches it was named after: it dropped the visits and left the
     * queries, and the list on screen came straight back.
     */
    public static void clearQueries(Context context) {
        prefs(context).edit().remove(KEY_QUERIES).apply();
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
        while (queries.size() > MAX_QUERIES) queries.remove(queries.size() - 1);

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
                o.put("n", entry.visits);
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
