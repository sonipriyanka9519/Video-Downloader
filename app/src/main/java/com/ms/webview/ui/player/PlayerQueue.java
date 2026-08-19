package com.ms.webview.ui.player;

import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * What plays after this one — screen 09, panels C and D.
 *
 * <p>The queue is the library list exactly as the viewer had it: same order, same filter, same
 * collection, same search. That is the design's rule and it is the one that makes "next" mean
 * anything — a queue assembled by some other logic would advance to a video the viewer has no
 * reason to expect, and the countdown would be a surprise rather than a courtesy.
 *
 * <p>Carried in the intent rather than read from the repository at the far end. The library is
 * narrowed by state that lives on the downloads screen — a chip, a search box, an open
 * collection — and none of that is knowable from inside the player. Sending the answer is
 * simpler and cannot disagree with what was on screen when the video was tapped.
 */
public final class PlayerQueue {

    private static final String EXTRA_URIS = "queue_uris";
    private static final String EXTRA_TITLES = "queue_titles";
    private static final String EXTRA_DURATIONS = "queue_durations";
    private static final String EXTRA_SIZES = "queue_sizes";
    private static final String EXTRA_POSTERS = "queue_posters";
    private static final String EXTRA_SCOPE = "queue_scope";

    /** One entry. Deliberately flat — the player needs a name, a length and a picture, not a row. */
    public static final class Item {
        public final String uri;
        public final String title;
        public final long durationMs;
        /** What the file weighs. Only the library knows it, so it travels with the row. */
        public final long sizeBytes;
        @Nullable
        public final String posterUrl;

        Item(String uri, String title, long durationMs, long sizeBytes,
             @Nullable String posterUrl) {
            this.uri = uri;
            this.title = title;
            this.durationMs = durationMs;
            this.sizeBytes = sizeBytes;
            this.posterUrl = posterUrl;
        }
    }

    private final List<Item> items = new ArrayList<>();
    /** What the list was narrowed by, for the sheet's caption. Empty when it was not. */
    private final String scope;
    private int index;

    private PlayerQueue(List<Item> items, String scope, int index) {
        this.items.addAll(items);
        this.scope = scope == null ? "" : scope;
        this.index = Math.max(0, index);
    }

    /**
     * Writes a queue into the intent that opens the player.
     *
     * <p>Parallel arrays rather than a Parcelable: four primitives per row, no versioning to get
     * wrong, and nothing that has to stay in step with a class definition across a process that
     * the system may have restarted.
     */
    public static void putInto(@NonNull Intent intent, @NonNull List<Item> items,
                               @Nullable String scope) {
        if (items.isEmpty()) return;

        String[] uris = new String[items.size()];
        String[] titles = new String[items.size()];
        long[] durations = new long[items.size()];
        long[] sizes = new long[items.size()];
        String[] posters = new String[items.size()];
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            uris[i] = item.uri;
            titles[i] = item.title;
            durations[i] = item.durationMs;
            sizes[i] = item.sizeBytes;
            posters[i] = item.posterUrl;
        }
        intent.putExtra(EXTRA_URIS, uris)
                .putExtra(EXTRA_TITLES, titles)
                .putExtra(EXTRA_DURATIONS, durations)
                .putExtra(EXTRA_SIZES, sizes)
                .putExtra(EXTRA_POSTERS, posters)
                .putExtra(EXTRA_SCOPE, scope);
    }

    /** Builds one row, for the caller assembling the list. */
    public static Item item(String uri, String title, long durationMs, long sizeBytes,
                            @Nullable String poster) {
        return new Item(uri, title, durationMs, sizeBytes, poster);
    }

    /**
     * Reads the queue back, positioned on the video actually being opened.
     *
     * <p>Returns an empty queue rather than null when there is nothing to read, so every caller
     * can ask it questions without checking first. An empty queue simply has no next.
     */
    @NonNull
    public static PlayerQueue readFrom(@Nullable Intent intent, @Nullable String playingUri) {
        List<Item> items = new ArrayList<>();
        String scope = "";
        int index = 0;

        if (intent != null) {
            String[] uris = intent.getStringArrayExtra(EXTRA_URIS);
            String[] titles = intent.getStringArrayExtra(EXTRA_TITLES);
            long[] durations = intent.getLongArrayExtra(EXTRA_DURATIONS);
            long[] sizes = intent.getLongArrayExtra(EXTRA_SIZES);
            String[] posters = intent.getStringArrayExtra(EXTRA_POSTERS);
            scope = intent.getStringExtra(EXTRA_SCOPE);

            // Every array has to be there and agree, or the row it describes is half a row. An
            // intent can be rebuilt by the system and arrive with less than it left with.
            if (uris != null && titles != null && durations != null
                    && titles.length == uris.length && durations.length == uris.length) {
                for (int i = 0; i < uris.length; i++) {
                    if (TextUtils.isEmpty(uris[i])) continue;
                    // The optional two are checked separately: a row is still usable without a
                    // picture or a size, and dropping the whole queue over either would be a
                    // worse answer than a row that shows a little less.
                    String poster = posters != null && posters.length == uris.length
                            ? posters[i] : null;
                    long size = sizes != null && sizes.length == uris.length ? sizes[i] : 0L;
                    items.add(new Item(uris[i], titles[i], durations[i], size, poster));
                    if (uris[i].equals(playingUri)) index = items.size() - 1;
                }
            }
        }
        return new PlayerQueue(items, scope, index);
    }

    public boolean isEmpty() {
        return items.size() <= 1;
    }

    public int size() {
        return items.size();
    }

    public int index() {
        return index;
    }

    @NonNull
    public List<Item> items() {
        return items;
    }

    @NonNull
    public String scope() {
        return scope;
    }

    /** How many are still to come. What the count beside the queue icon shows. */
    public int remaining() {
        return Math.max(0, items.size() - index - 1);
    }

    public boolean hasNext() {
        return index + 1 < items.size();
    }

    public boolean hasPrevious() {
        return index > 0;
    }

    @Nullable
    public Item at(int position) {
        return position >= 0 && position < items.size() ? items.get(position) : null;
    }

    @Nullable
    public Item next() {
        return at(index + 1);
    }

    /** Moves to a row and returns it, or null when the row is not there. */
    @Nullable
    public Item moveTo(int position) {
        Item item = at(position);
        if (item != null) index = position;
        return item;
    }
}
