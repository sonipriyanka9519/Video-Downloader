package com.ms.webview.ui.downloads;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collections — screen 08. Lightweight folders a download is never asked to belong to.
 *
 * <p>One level deep, no nesting and no colours, because the moment folders have structure they
 * have to be maintained, and this is meant to be something you can use once and forget.
 *
 * <p>Held as JSON in preferences rather than as a Room table. A collection is a name and a list
 * of uris — the library rows themselves already live in the database, and this only says which
 * of them are grouped. Keeping it out of the schema means the download engine's storage is not
 * touched to add a filing feature.
 *
 * <p>Membership is by published uri, the same key {@link WatchedStore} uses: it is the one
 * identifier that survives a rename.
 *
 * <p>Insertion order is kept, so the list does not reshuffle itself between openings.
 */
public final class CollectionStore {

    private static final String TAG = "CollectionStore";

    private static final String PREFS = "collections";
    private static final String KEY = "data";

    private static final String J_NAME = "name";
    private static final String J_ITEMS = "items";

    /** Enough for the folders someone actually keeps, and a stop on a runaway loop. */
    public static final int MAX_NAME_LENGTH = 40;

    /**
     * Parsed once and kept, because the sheet asks for a count per row and the library asks for
     * a membership set per bind. Every write goes through {@link #commit}, which is the only
     * thing that replaces it, so the copy cannot drift from the file.
     */
    @Nullable
    private static Map<String, Set<String>> cache;

    private CollectionStore() {
    }

    /** Every collection, in the order they were made. */
    @NonNull
    public static List<String> names(Context context) {
        return new ArrayList<>(load(context).keySet());
    }

    public static boolean isEmpty(Context context) {
        return load(context).isEmpty();
    }

    /** How many videos are filed under a name. Zero for a name that does not exist. */
    public static int count(Context context, String name) {
        Set<String> items = load(context).get(name);
        return items == null ? 0 : items.size();
    }

    /** The uris filed under a name — a copy, so a caller cannot edit the store by accident. */
    @NonNull
    public static Set<String> members(Context context, String name) {
        Set<String> items = load(context).get(name);
        return items == null ? Collections.emptySet() : new LinkedHashSet<>(items);
    }

    public static boolean contains(Context context, String name, String uri) {
        Set<String> items = load(context).get(name);
        return items != null && items.contains(uri);
    }

    /** Whether any collection holds this video at all — what a menu row reads to name itself. */
    public static boolean holds(Context context, @Nullable String uri) {
        if (TextUtils.isEmpty(uri)) return false;
        for (Set<String> items : load(context).values()) {
            if (items.contains(uri)) return true;
        }
        return false;
    }

    /**
     * Makes an empty collection.
     *
     * @return false when the name is blank or already taken — the caller says why, since only it
     * knows where the name came from.
     */
    public static boolean create(Context context, String name) {
        String clean = clean(name);
        if (clean == null) return false;

        Map<String, Set<String>> data = load(context);
        if (data.containsKey(clean)) return false;

        data.put(clean, new LinkedHashSet<>());
        commit(context, data);
        return true;
    }

    /** Files videos under a name, making the collection if it is not there yet. */
    public static void add(Context context, String name, Collection<String> uris) {
        String clean = clean(name);
        if (clean == null || uris == null || uris.isEmpty()) return;

        Map<String, Set<String>> data = load(context);
        Set<String> items = data.get(clean);
        if (items == null) {
            items = new LinkedHashSet<>();
            data.put(clean, items);
        }
        for (String uri : uris) {
            if (!TextUtils.isEmpty(uri)) items.add(uri);
        }
        commit(context, data);
    }

    public static void remove(Context context, String name, String uri) {
        Map<String, Set<String>> data = load(context);
        Set<String> items = data.get(name);
        if (items == null || !items.remove(uri)) return;
        commit(context, data);
    }

    /**
     * Drops the collection and keeps every video in it.
     *
     * <p>The distinction is the whole point of the confirm the caller shows: this is a label
     * coming off, not files going away.
     */
    public static void delete(Context context, String name) {
        Map<String, Set<String>> data = load(context);
        if (data.remove(name) == null) return;
        commit(context, data);
    }

    /** @return false when the new name is blank or already taken. */
    public static boolean rename(Context context, String from, String to) {
        String clean = clean(to);
        if (clean == null) return false;

        Map<String, Set<String>> data = load(context);
        if (!data.containsKey(from)) return false;
        if (data.containsKey(clean) && !clean.equals(from)) return false;

        // Rebuilt rather than put-and-remove: a LinkedHashMap would move the renamed collection
        // to the end, and the list someone knows the shape of would rearrange itself.
        Map<String, Set<String>> next = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : data.entrySet()) {
            next.put(e.getKey().equals(from) ? clean : e.getKey(), e.getValue());
        }
        commit(context, next);
        return true;
    }

    /**
     * Takes a deleted download out of every collection it was in.
     *
     * <p>Without this a collection's count keeps counting files that are gone, and the filtered
     * list quietly shrinks below the number on the row that opened it.
     */
    public static void forget(Context context, String uri) {
        if (TextUtils.isEmpty(uri)) return;

        Map<String, Set<String>> data = load(context);
        boolean changed = false;
        for (Set<String> items : data.values()) {
            changed |= items.remove(uri);
        }
        if (changed) commit(context, data);
    }

    /**
     * Drops everything filed here that no longer exists.
     *
     * <p>{@link #forget} covers a delete this app performed and watched happen. It cannot cover
     * the two that matter most: a delete the system carried out itself after asking the viewer,
     * and a file removed in the gallery while this app was closed. Both leave a collection
     * counting videos that are not there, and the count on the sheet is the first place it shows.
     *
     * <p>Reconciled against the library rather than tracked, because the library is the only
     * thing that knows what is really on the device.
     *
     * @param live every uri the library currently holds. Ignored when empty — a library still
     *             loading looks exactly like a library with nothing in it, and acting on that
     *             would empty every collection on the way to the first frame.
     */
    public static void retainAll(Context context, Set<String> live) {
        if (live == null || live.isEmpty()) return;

        Map<String, Set<String>> data = load(context);
        boolean changed = false;
        for (Set<String> items : data.values()) {
            changed |= items.retainAll(live);
        }
        if (changed) commit(context, data);
    }

    // ------------------------------------------------------------------ storage

    @Nullable
    private static String clean(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_NAME_LENGTH
                ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    @NonNull
    private static Map<String, Set<String>> load(Context context) {
        if (cache != null) return cache;

        Map<String, Set<String>> data = new LinkedHashMap<>();
        String raw = prefs(context).getString(KEY, null);
        if (!TextUtils.isEmpty(raw)) {
            try {
                JSONArray all = new JSONArray(raw);
                for (int i = 0; i < all.length(); i++) {
                    JSONObject one = all.optJSONObject(i);
                    if (one == null) continue;

                    String name = one.optString(J_NAME, "");
                    if (TextUtils.isEmpty(name)) continue;

                    Set<String> items = new LinkedHashSet<>();
                    JSONArray uris = one.optJSONArray(J_ITEMS);
                    if (uris != null) {
                        for (int j = 0; j < uris.length(); j++) {
                            String uri = uris.optString(j, "");
                            if (!TextUtils.isEmpty(uri)) items.add(uri);
                        }
                    }
                    data.put(name, items);
                }
            } catch (JSONException e) {
                // Losing the folders is bad; refusing to open the library because of them is
                // worse. Start clean and say so, rather than throwing out of a bind.
                Log.w(TAG, "collections unreadable, starting empty", e);
                data.clear();
            }
        }
        cache = data;
        return data;
    }

    private static void commit(Context context, Map<String, Set<String>> data) {
        JSONArray all = new JSONArray();
        for (Map.Entry<String, Set<String>> e : data.entrySet()) {
            JSONObject one = new JSONObject();
            try {
                one.put(J_NAME, e.getKey());
                one.put(J_ITEMS, new JSONArray(e.getValue()));
            } catch (JSONException ex) {
                Log.w(TAG, "could not write collection " + e.getKey(), ex);
                continue;
            }
            all.put(one);
        }
        cache = data;
        prefs(context).edit().putString(KEY, all.toString()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
