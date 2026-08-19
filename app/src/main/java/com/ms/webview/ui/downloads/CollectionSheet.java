package com.ms.webview.ui.downloads;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.ms.webview.R;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.ui.Thumbnails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

/**
 * The collections sheet — screen 08, both of its modes.
 *
 * <p>Raised from the chip it filters: one tap on a name closes it and narrows the library.
 * Raised from a video it files: every row grows a tick, several can be chosen at once, and a
 * button at the foot commits them. One class rather than two because the list, the create row
 * and the empty state are identical, and only the tail of each row differs.
 *
 * <p>Creating is inline, per the design: the "New collection" row turns into a field with a
 * Create button beside it. Nothing is ever forced — a video can be downloaded, watched and
 * deleted without a collection existing at all.
 */
public final class CollectionSheet {

    /** What the sheet does when a name is tapped. */
    public interface Listener {

        /**
         * Filter mode: show only this collection, or the whole library when null.
         *
         * <p>Null is passed by the clear affordance rather than by an empty name, so a
         * collection literally called "All" could never be mistaken for it.
         */
        void onCollectionChosen(@Nullable String name);

        /** Add mode: the chosen videos have been filed. */
        void onCollectionsFiled(String message);
    }

    private final Context context;
    private final Listener listener;

    /**
     * The videos this sheet is about to file, empty in filter mode.
     *
     * <p>Which mode the sheet is in is decided by this being empty or not, rather than by a flag
     * beside it — the two could otherwise disagree, and the disagreement would be a sheet
     * offering to file nothing.
     */
    private final List<String> subject = new ArrayList<>();

    /** Add mode only: the collections ticked so far. */
    private final Set<String> ticked = new LinkedHashSet<>();

    /**
     * Which collections these videos were already in when the sheet opened.
     *
     * <p>This is what turns add mode into membership: a row that starts ticked can be <em>un</em>
     * ticked, and the difference between this and {@link #ticked} on commit is what is added and
     * what is taken out. Filing used to be one-way, which meant a video in two collections could
     * never be reduced to one from anywhere in the app.
     *
     * <p>A collection only counts as "already in" when it holds <b>every</b> video in the subject.
     * One that holds two of the four selected is deliberately left unticked and therefore untouched
     * — an unticked row that was never ticked removes nothing, so a batch cannot quietly empty a
     * collection nobody was looking at.
     */
    private final Set<String> initial = new LinkedHashSet<>();

    private BottomSheetDialog dialog;
    private LinearLayout list;
    private TextView empty;
    private View newRow;
    private View createRow;
    private EditText nameInput;
    private MaterialButton addButton;

    /**
     * The library, keyed by the uri collections file things under.
     *
     * <p>Held so a row can wear its newest video as its cover. The store knows which uris are in
     * a collection and nothing else about them — no poster, no date — so the pictures have to
     * come from the one place that has them.
     */
    private final Map<String, DownloadEntity> library = new HashMap<>();

    private CollectionSheet(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Filter mode — pick a collection to narrow the library to. */
    public static CollectionSheet filter(Context context, Listener listener) {
        return new CollectionSheet(context, listener);
    }

    /** Add mode — file these videos into one or more collections. */
    public static CollectionSheet add(Context context, Collection<String> uris,
                                      Listener listener) {
        CollectionSheet sheet = new CollectionSheet(context, listener);
        if (uris != null) {
            for (String uri : uris) {
                if (!TextUtils.isEmpty(uri)) sheet.subject.add(uri);
            }
        }
        return sheet;
    }

    /** What each row draws its cover from. Optional — without it, rows wear the folder glyph. */
    public CollectionSheet withLibrary(@Nullable List<DownloadEntity> items) {
        if (items != null) {
            for (DownloadEntity d : items) {
                if (d != null && !TextUtils.isEmpty(d.outputUri)) library.put(d.outputUri, d);
            }
        }
        return this;
    }

    private boolean addMode() {
        return !subject.isEmpty();
    }

    /**
     * Ticks the collections these videos are already in, before the list is first drawn.
     *
     * <p>Every subject has to be in a collection for it to count — see {@link #initial}.
     */
    private void readMembership() {
        initial.clear();
        for (String name : CollectionStore.names(context)) {
            boolean all = true;
            for (String uri : subject) {
                if (!CollectionStore.contains(context, name, uri)) {
                    all = false;
                    break;
                }
            }
            if (all) initial.add(name);
        }
        ticked.clear();
        ticked.addAll(initial);
    }

    public void show() {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.sheet_collections, null, false);

        list = content.findViewById(R.id.collectionsList);
        empty = content.findViewById(R.id.collectionsEmpty);
        newRow = content.findViewById(R.id.collectionNew);
        createRow = content.findViewById(R.id.collectionCreateRow);
        nameInput = content.findViewById(R.id.collectionNameInput);
        addButton = content.findViewById(R.id.btnCollectionAdd);

        TextView title = content.findViewById(R.id.collectionsTitle);
        TextView subjectLabel = content.findViewById(R.id.collectionsSubject);

        if (addMode()) {
            readMembership();
            // "Add to collection" is only true the first time. Once these videos are in one, the
            // sheet is showing where they are and letting that be changed, and a heading that
            // said "add" would make an untickable-looking row out of the one they are already in.
            title.setText(initial.isEmpty()
                    ? R.string.collections_add_title : R.string.collections_in_title);
            subjectLabel.setVisibility(View.VISIBLE);
            subjectLabel.setText(context.getResources().getQuantityString(
                    R.plurals.videos_count, subject.size(), subject.size()));
            addButton.setVisibility(View.VISIBLE);
        }

        newRow.setOnClickListener(v -> showCreateRow());
        content.findViewById(R.id.btnCollectionCreate).setOnClickListener(v -> create());
        addButton.setOnClickListener(v -> commitAdd());

        rebuild();
        updateAddButton();

        dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_Ds_BottomSheet);
        dialog.setContentView(content);
        dialog.show();
    }

    public void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    // ------------------------------------------------------------------ the list

    /**
     * Redraws every row.
     *
     * <p>Cheap, and a collection list is a handful of names — rebuilding is simpler to be sure
     * of than patching one row after a create, and there is no scroll position worth keeping
     * across a change somebody just made.
     */
    private void rebuild() {
        list.removeAllViews();

        List<String> names = CollectionStore.names(context);
        empty.setVisibility(names.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = LayoutInflater.from(context);
        for (String name : names) {
            list.addView(row(inflater, list, name));
        }
    }

    private View row(LayoutInflater inflater, ViewGroup parent, String name) {
        View row = inflater.inflate(R.layout.item_collection, parent, false);

        int count = CollectionStore.count(context, name);
        ((TextView) row.findViewById(R.id.collectionName)).setText(name);
        ((TextView) row.findViewById(R.id.collectionCount)).setText(
                context.getResources().getQuantityString(R.plurals.videos_count, count, count));
        bindCover(row, name);

        View tick = row.findViewById(R.id.collectionTick);
        ImageView check = row.findViewById(R.id.collectionCheck);

        if (addMode()) {
            tick.setVisibility(View.VISIBLE);
            bindTick(row, check, ticked.contains(name));
            row.setOnClickListener(v -> {
                boolean now = !ticked.contains(name);
                if (now) {
                    ticked.add(name);
                } else {
                    ticked.remove(name);
                }
                bindTick(row, check, now);
                updateAddButton();
            });
        } else {
            row.setOnClickListener(v -> {
                listener.onCollectionChosen(name);
                dismiss();
            });
        }
        return row;
    }

    /**
     * The newest video in the collection, as the row's cover.
     *
     * <p>Newest rather than first: a collection is added to over time, and the picture people
     * recognise it by should be the last thing they put in it rather than whatever started it.
     *
     * <p>Falls back to the folder glyph rather than to a blank square — an empty collection and
     * one whose cover failed to load must not look the same.
     */
    private void bindCover(View row, String name) {
        ImageView cover = row.findViewById(R.id.collectionCover);
        View folder = row.findViewById(R.id.collectionFolder);

        DownloadEntity newest = null;
        for (String uri : CollectionStore.members(context, name)) {
            DownloadEntity d = library.get(uri);
            if (d == null) continue;
            if (newest == null || savedAt(d) > savedAt(newest)) newest = d;
        }

        // Sound has no frame to show, so it keeps the glyph too — the same answer the library
        // rows give, rather than a stretched piece of album art.
        if (newest == null || newest.kind == MediaKind.AUDIO
                || TextUtils.isEmpty(newest.posterUrl)) {
            cover.setVisibility(View.GONE);
            folder.setVisibility(View.VISIBLE);
            return;
        }

        cover.setVisibility(View.VISIBLE);
        folder.setVisibility(View.GONE);
        // Same headers the media was captured with: CDN posters 403 a bare image request.
        Thumbnails.load(cover, newest.posterUrl, newest.headers());
    }

    private static long savedAt(DownloadEntity d) {
        return d.completedAt > 0 ? d.completedAt : d.createdAt;
    }

    /** The tick's fill comes from the row's selected state; the glyph has to be told. */
    private void bindTick(View row, ImageView check, boolean on) {
        row.setSelected(on);
        check.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ creating

    private void showCreateRow() {
        newRow.setVisibility(View.GONE);
        createRow.setVisibility(View.VISIBLE);
        nameInput.requestFocus();

        InputMethodManager ime = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void create() {
        String name = nameInput.getText() == null ? "" : nameInput.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            // Says which of the two things is wrong. "Could not create" would leave somebody
            // retyping the same duplicate name.
            nameInput.setError(context.getString(R.string.collection_name_required));
            return;
        }
        if (!CollectionStore.create(context, name)) {
            nameInput.setError(context.getString(R.string.collection_name_taken));
            return;
        }

        nameInput.setText("");
        nameInput.setError(null);
        createRow.setVisibility(View.GONE);
        newRow.setVisibility(View.VISIBLE);
        hideKeyboard();

        // In add mode, making a collection is nearly always the first half of filing into it. Not
        // added to `initial` — it is a change, and the button should light up because of it.
        if (addMode()) ticked.add(name);

        rebuild();
        updateAddButton();
    }

    private void hideKeyboard() {
        InputMethodManager ime = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime != null) ime.hideSoftInputFromWindow(nameInput.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ filing

    /**
     * The button names where the videos are going.
     *
     * <p>One collection is named outright, because that is the fact somebody is checking before
     * they commit. Several are counted instead — four names would not fit and would not be read.
     */
    private void updateAddButton() {
        if (!addMode()) return;

        // Nothing to file yet: the design's own three labels, unchanged, for the first time a video
        // is put anywhere.
        if (initial.isEmpty()) {
            boolean any = !ticked.isEmpty();
            addButton.setEnabled(any);
            if (ticked.size() == 1) {
                addButton.setText(context.getString(R.string.collection_add_to,
                        ticked.iterator().next()));
            } else if (any) {
                addButton.setText(context.getResources().getQuantityString(
                        R.plurals.collection_add_to_many, ticked.size(), ticked.size()));
            } else {
                addButton.setText(R.string.collection_add_choose);
            }
            return;
        }

        // Already filed somewhere, so the button is committing a change rather than an addition —
        // and "Add to Holiday" over a tick that was just taken away would describe the opposite of
        // what pressing it does. Inert until something actually differs.
        addButton.setText(R.string.save);
        addButton.setEnabled(!ticked.equals(initial));
    }

    private void commitAdd() {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (String name : ticked) {
            if (initial.contains(name)) continue;
            CollectionStore.add(context, name, subject);
            added.add(name);
        }
        // Only rows that were shown ticked can lose anything. A collection holding some of the
        // selection was never ticked, so it is never touched here.
        for (String name : initial) {
            if (ticked.contains(name)) continue;
            for (String uri : subject) CollectionStore.remove(context, name, uri);
            removed.add(name);
        }
        if (added.isEmpty() && removed.isEmpty()) return;

        dismiss();
        listener.onCollectionsFiled(message(added, removed));
    }

    /**
     * What just happened, in one line.
     *
     * <p>One collection is named outright, because that is the fact somebody is checking after they
     * commit. Several are counted instead — four names would not fit and would not be read. A change
     * that both added and removed is neither, so it says so plainly rather than telling half of it.
     */
    private String message(List<String> added, List<String> removed) {
        if (!added.isEmpty() && !removed.isEmpty()) {
            return context.getString(R.string.collections_updated);
        }
        if (removed.isEmpty()) {
            return added.size() == 1
                    ? context.getString(R.string.collection_added_one, added.get(0))
                    : context.getResources().getQuantityString(
                            R.plurals.collection_added_many, added.size(), added.size());
        }
        return removed.size() == 1
                ? context.getString(R.string.collection_removed_from, removed.get(0))
                : context.getResources().getQuantityString(
                        R.plurals.collection_removed_many, removed.size(), removed.size());
    }
}
