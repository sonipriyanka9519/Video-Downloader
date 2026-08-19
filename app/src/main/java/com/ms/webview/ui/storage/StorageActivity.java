package com.ms.webview.ui.storage;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.App;
import com.ms.webview.MainActivity;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.data.MediaLibrary;
import com.ms.webview.ui.DownloadsFragment;
import com.ms.webview.ui.PlayerActivity;
import com.ms.webview.ui.SystemBars;
import com.ms.webview.ui.Thumbnails;
import com.ms.webview.ui.downloads.CollectionStore;
import com.ms.webview.ui.downloads.WatchedStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Screen 13 — Storage.
 *
 * <p>Built against the storage-full uninstall. It answers three questions in the order somebody in
 * that position asks them: how much is this app using, what can I safely delete, and what is
 * biggest.
 *
 * <p><b>Nothing here deletes anything on its own.</b> The one bulk action hands off to the library's
 * select mode with the watched videos already ticked — the same select mode, the same confirm, the
 * same consent dialog. A second place in the app that removed files would be a second place to get
 * that wrong.
 *
 * <p>Private videos are not counted as videos here, and that is deliberate: they are not in the
 * library, and a figure on this screen that only adds up once you know about a hidden folder would
 * be the one surface that gives the folder away. Their bytes land in "Other content", which is where
 * everything else on the phone is, and is not untrue.
 */
public class StorageActivity extends AppCompatActivity {

    /** How many of the largest files to list. Enough to find the culprit, short enough to scan. */
    private static final int LARGEST_COUNT = 5;

    private TextView used;
    private TextView freeLine;
    private View barVideos;
    private View barOther;
    private View barFree;
    private View watchedCard;
    private View nothingCard;
    private TextView watchedSummary;
    private LinearLayout largestList;
    private View largestEmpty;

    @Nullable
    private BottomSheetDialog sheet;

    public static void open(Context context) {
        context.startActivity(new Intent(context, StorageActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_storage);

        used = findViewById(R.id.storageUsed);
        freeLine = findViewById(R.id.storageFree);
        barVideos = findViewById(R.id.barVideos);
        barOther = findViewById(R.id.barOther);
        barFree = findViewById(R.id.barFree);
        watchedCard = findViewById(R.id.watchedCard);
        nothingCard = findViewById(R.id.nothingCard);
        watchedSummary = findViewById(R.id.watchedSummary);
        largestList = findViewById(R.id.largestList);
        largestEmpty = findViewById(R.id.largestEmpty);

        SystemBars.pad(findViewById(R.id.storageRoot));
        findViewById(R.id.btnStorageBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnReviewWatched).setOnClickListener(v -> reviewWatched());
    }

    /**
     * Rebuilt on every return rather than observed.
     *
     * <p>The numbers change while this screen is in the background — a delete carried out in the
     * library it handed off to, a download that finished — and a storage screen showing figures from
     * a minute ago is the one thing it must not do.
     */
    @Override
    protected void onResume() {
        super.onResume();
        App.get().repository().refreshLibrary();
        bind();
    }

    @Override
    protected void onDestroy() {
        dismissSheet();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ the figures

    private void bind() {
        List<DownloadEntity> saved = savedVideos();

        long ours = 0;
        for (DownloadEntity d : saved) ours += sizeOf(d);

        // The volume the app's files actually live on, so the total is the one the viewer would see
        // in their own settings rather than the internal partition's.
        File home = getExternalFilesDir(null);
        StatFs stat = new StatFs((home != null ? home : Environment.getDataDirectory()).getPath());
        long total = stat.getTotalBytes();
        long free = stat.getAvailableBytes();
        // Everything on the phone that is not free and not ours. Clamped: the two figures come from
        // different places and a rounding disagreement must not become a negative segment.
        long other = Math.max(0, total - free - ours);

        used.setText(getResources().getQuantityString(
                R.plurals.storage_used_by, saved.size(), Formats.bytes(ours), saved.size()));
        freeLine.setText(getString(R.string.storage_free_of,
                Formats.bytes(free), Formats.bytes(total)));

        weigh(barVideos, ours);
        weigh(barOther, other);
        weigh(barFree, free);

        legend(R.id.legendVideos, R.color.ds_accent,
                getString(R.string.storage_legend_videos, Formats.bytes(ours)));
        legend(R.id.legendOther, R.color.ds_ink_faint,
                getString(R.string.storage_legend_other, Formats.bytes(other)));
        legend(R.id.legendFree, R.color.ds_line,
                getString(R.string.storage_legend_free, Formats.bytes(free)));

        bindWatched(saved);
        bindLargest(saved);
    }

    /**
     * A segment's share of the bar.
     *
     * <p>Weights rather than measured widths, so the bar is right before it has been laid out and
     * stays right through a rotation. A zero-byte segment gets a zero weight and disappears
     * entirely — except videos, which keep a minimum width in the layout so the app's own slice is
     * visible even at a fraction of a percent of a large phone.
     */
    private static void weigh(View segment, long bytes) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) segment.getLayoutParams();
        // Scaled down to megabytes: a float weight of 5×10^10 loses precision where it matters.
        params.weight = Math.max(0f, bytes / (1024f * 1024f));
        segment.setLayoutParams(params);
    }

    private void legend(int rowId, @ColorRes int swatch, String label) {
        View row = findViewById(rowId);
        View dot = row.findViewById(R.id.legendDot);

        // One drawable tinted three ways rather than three drawables: the colour is the only
        // difference between them, and it is the same colour the bar is using two views up.
        //
        // Mutated first. Without that, all three swatches share one constant state and the last tint
        // set wins for every one of them.
        Drawable swatchBg = dot.getBackground();
        if (swatchBg != null) {
            swatchBg = swatchBg.mutate();
            swatchBg.setTint(ContextCompat.getColor(this, swatch));
            dot.setBackground(swatchBg);
        }
        ((TextView) row.findViewById(R.id.legendLabel)).setText(label);
    }

    // ------------------------------------------------------------------ what can go

    /**
     * The one suggestion: videos already finished.
     *
     * <p>Finished, not merely started — {@link WatchedStore} calls anything past 95% watched, which
     * is the same line Continue Watching uses to decide a video is done with. Suggesting something
     * half-watched would be suggesting somebody delete what they are in the middle of.
     */
    private void bindWatched(List<DownloadEntity> saved) {
        long bytes = 0;
        int count = 0;
        for (DownloadEntity d : saved) {
            if (WatchedStore.isUnwatched(this, d.outputUri)) continue;
            // Never watched at all reads as unwatched, and isUnwatched agrees — but a video with no
            // progress recorded has nothing to be finished with, so it cannot reach here.
            count++;
            bytes += sizeOf(d);
        }

        boolean anything = count > 0;
        watchedCard.setVisibility(anything ? View.VISIBLE : View.GONE);
        nothingCard.setVisibility(anything ? View.GONE : View.VISIBLE);
        if (anything) {
            watchedSummary.setText(getResources().getQuantityString(
                    R.plurals.storage_watched_summary, count, count, Formats.bytes(bytes)));
        }
    }

    /**
     * Hands off to screen 07's select mode with the watched videos ticked.
     *
     * <p>The request is left for the library to pick up rather than passed as a list: the fragment
     * may not exist yet, and it holds the live rows anyway — a list of ids captured here would be a
     * minute old by the time anything was deleted.
     */
    private void reviewWatched() {
        DownloadsFragment.reviewWatchedWhenReady();
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true));
        // Left behind rather than kept: coming back from the library to a storage screen showing
        // the figures from before the deleting would be worse than not being here at all.
        finish();
    }

    // ------------------------------------------------------------------ largest files

    private void bindLargest(List<DownloadEntity> saved) {
        largestList.removeAllViews();

        List<DownloadEntity> biggest = new ArrayList<>(saved);
        Collections.sort(biggest, new Comparator<DownloadEntity>() {
            @Override
            public int compare(DownloadEntity a, DownloadEntity b) {
                return Long.compare(sizeOf(b), sizeOf(a));
            }
        });

        largestEmpty.setVisibility(biggest.isEmpty() ? View.VISIBLE : View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < Math.min(LARGEST_COUNT, biggest.size()); i++) {
            largestList.addView(largestRow(inflater, largestList, biggest.get(i)));
        }
    }

    private View largestRow(LayoutInflater inflater, ViewGroup parent, DownloadEntity d) {
        View row = inflater.inflate(R.layout.item_storage_large, parent, false);

        ((TextView) row.findViewById(R.id.largeTitle)).setText(
                TextUtils.isEmpty(d.title) ? d.fileName : d.title);
        ((TextView) row.findViewById(R.id.largeMeta)).setText(metaOf(d));
        ((TextView) row.findViewById(R.id.largeSize)).setText(Formats.bytes(sizeOf(d)));

        ImageView thumb = row.findViewById(R.id.largeThumb);
        Thumbnails.load(thumb, d.posterUrl, d.headers());

        row.setOnClickListener(v -> play(d));
        row.findViewById(R.id.largeMore).setOnClickListener(v -> showMore(d));
        return row;
    }

    /** "1080p • 22:14" — quality and length, since the size is already its own column. */
    private String metaOf(DownloadEntity d) {
        String quality = TextUtils.isEmpty(d.quality) ? "" : d.quality;
        String length = Formats.duration(d.durationMs);
        if (quality.isEmpty()) return length;
        if (length.isEmpty()) return quality;
        return quality + " • " + length;
    }

    private static long sizeOf(DownloadEntity d) {
        return d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes;
    }

    /** Completed downloads with a file behind them — the only things that occupy any space. */
    private List<DownloadEntity> savedVideos() {
        List<DownloadEntity> all = App.get().repository().observeAll().getValue();
        List<DownloadEntity> out = new ArrayList<>();
        if (all == null) return out;

        for (DownloadEntity d : all) {
            if (d.status == DownloadStatus.COMPLETED && !TextUtils.isEmpty(d.outputUri)) out.add(d);
        }
        return out;
    }

    // ------------------------------------------------------------------ one row's actions

    /**
     * Play or delete, and nothing else.
     *
     * <p>The library's sheet, with the rest of its rows hidden: renaming, filing and making private
     * are all things somebody does while looking at their library, not while looking at a storage
     * figure. The two that belong here are the two the design promises — a single fix, one tap away.
     */
    private void showMore(DownloadEntity d) {
        View content = LayoutInflater.from(this)
                .inflate(R.layout.sheet_download_more, null, false);

        ((TextView) content.findViewById(R.id.moreName)).setText(
                TextUtils.isEmpty(d.fileName) ? d.title : d.fileName);
        ((TextView) content.findViewById(R.id.moreMeta)).setText(
                Formats.bytes(sizeOf(d)) + " • " + metaOf(d));
        Thumbnails.load(content.findViewById(R.id.moreThumb), d.posterUrl, d.headers());

        for (int id : new int[]{R.id.moreShare, R.id.moreRename, R.id.moreCollection,
                R.id.morePrivate, R.id.moreProperty}) {
            content.findViewById(id).setVisibility(View.GONE);
        }

        sheet = new BottomSheetDialog(this, R.style.ThemeOverlay_Ds_BottomSheet);
        sheet.setContentView(content);
        content.findViewById(R.id.morePlay).setOnClickListener(v -> {
            dismissSheet();
            play(d);
        });
        content.findViewById(R.id.moreDelete).setOnClickListener(v -> {
            dismissSheet();
            confirmDelete(d);
        });
        sheet.show();
    }

    private void play(DownloadEntity d) {
        PlayerActivity.open(this, d.outputUri, d.title);
    }

    /**
     * The same confirm the library uses, because it is the same deletion.
     *
     * <p>What this screen cannot do is ask the system for permission on a file the app does not own
     * — that needs the launcher the library already has. Such a file is reported honestly instead of
     * failing silently, and it can still be deleted from the library itself.
     */
    private void confirmDelete(DownloadEntity d) {
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, d.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    MediaLibrary.WriteResult result = App.get().repository().delete(d);
                    if (result.done) {
                        // The two side stores that know about this file have to forget it with it.
                        CollectionStore.forget(this, d.outputUri);
                        WatchedStore.forget(this, d.outputUri);
                        App.get().repository().refreshLibrary();
                        bind();
                        return;
                    }
                    new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Ds_Dialog)
                            .setMessage(R.string.storage_delete_elsewhere)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                })
                .show();
    }

    private void dismissSheet() {
        if (sheet != null) sheet.dismiss();
        sheet = null;
    }
}
