package com.ms.webview.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.detect.MediaItem;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.MediaVariant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One full page per detected video, swiped horizontally. */
public class MediaPagerAdapter extends RecyclerView.Adapter<MediaPagerAdapter.PageHolder> {

    /** Rungs to a row. Three fits a 360dp screen without any card being squeezed. */
    private static final int QUALITY_COLUMNS = 3;


    public interface OnDownload {
        void download(MediaItem item, MediaVariant variant);
    }

    /** Raised by the "Download all N…" button, which only appears on a page with several. */
    public interface OnDownloadAll {
        void downloadAll();
    }

    @Nullable
    private OnDownloadAll downloadAll;

    public void setOnDownloadAll(@Nullable OnDownloadAll listener) {
        this.downloadAll = listener;
    }

    private final List<MediaItem> items = new ArrayList<>();
    /** Chosen quality per video, keyed by group so it survives the frequent list refreshes. */
    private final Map<String, String> selection = new HashMap<>();
    /**
     * What each card was last bound with, keyed by group. Recorded rather than recomputed,
     * because the items themselves are live objects shared with the registry — see
     * {@link #submit(List)}.
     */
    private final Map<String, String> signatures = new HashMap<>();
    private final OnDownload callback;
    /**
     * Which rung is ticked before anybody taps one — screen 10's default-quality setting, mapped
     * through {@link BatchQuality} so it resolves against each video's own ladder rather than
     * against a number that may not exist on it.
     *
     * <p>Null means "Always ask": no rung is ticked and the download button waits. Read once when
     * the sheet opens, not per bind, so changing the setting mid-sheet cannot move the tick out
     * from under a finger.
     */
    @Nullable
    private BatchQuality preselect = BatchQuality.BEST_UNDER_720;

    public MediaPagerAdapter(OnDownload callback) {
        this.callback = callback;
    }

    /** @param choice null for "Always ask" — nothing preselected. */
    public void setPreselect(@Nullable BatchQuality choice) {
        this.preselect = choice;
    }

    /**
     * Diffed rather than reloaded wholesale: live frames arrive every couple of seconds, and
     * rebinding every page that often would reset chips and flicker the thumbnails.
     *
     * <p>Diffed against a <em>recorded</em> signature, which is the whole of why this works. The
     * registry publishes the same {@link MediaItem} objects every time — a new list holding the
     * same instances — so asking one for its signature twice and comparing the answers compared a
     * value with itself. Contents were therefore never unequal, no page was ever rebound, and
     * every card kept whatever it was first given: a NOW PLAYING badge that outlived the video
     * playing, so two cards claimed it at once and neither would let go.
     */
    public void submit(List<MediaItem> incoming) {
        List<MediaItem> next = incoming == null ? new ArrayList<>() : new ArrayList<>(incoming);
        List<MediaItem> previous = new ArrayList<>(items);

        // Both sides frozen before the comparison, so neither can change under it.
        Map<String, String> was = new HashMap<>(signatures);
        Map<String, String> now = new HashMap<>();
        for (MediaItem item : next) now.put(item.groupKey, signature(item));

        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return previous.size();
            }

            @Override
            public int getNewListSize() {
                return next.size();
            }

            @Override
            public boolean areItemsTheSame(int oldPos, int newPos) {
                return previous.get(oldPos).groupKey.equals(next.get(newPos).groupKey);
            }

            @Override
            public boolean areContentsTheSame(int oldPos, int newPos) {
                String before = was.get(previous.get(oldPos).groupKey);
                // Nothing recorded means nothing to trust: rebind rather than assume.
                return before != null && before.equals(now.get(next.get(newPos).groupKey));
            }
        });

        items.clear();
        items.addAll(next);
        signatures.clear();
        signatures.putAll(now);
        diff.dispatchUpdatesTo(this);
    }

    private static String signature(MediaItem item) {
        return item.displayTitle() + '|' + item.subtitle() + '|' + item.current + '|'
                + item.drmProtected + '|' + item.qualities().size() + '|'
                + String.valueOf(item.thumbnail()).hashCode();
    }

    public List<MediaItem> current() {
        return new ArrayList<>(items);
    }

    /**
     * Identity, not position.
     *
     * <p>The list re-sorts constantly — a new video arrives, the one on screen moves to the
     * front — so a page number means nothing between one refresh and the next. Everything the
     * sheet remembers about where it is, it remembers by group key.
     */
    @Nullable
    public String playingKey() {
        for (MediaItem item : items) {
            // The platform's answer, not the raw mark — the same one the badge shows, so the
            // sheet cannot open on one card while another is labelled as the current video.
            if (item.current) return item.groupKey;
        }
        return null;
    }

    public int indexOf(@Nullable String groupKey) {
        if (groupKey == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (groupKey.equals(items.get(i).groupKey)) return i;
        }
        return -1;
    }

    @Nullable
    public String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).groupKey : null;
    }

    /**
     * The variant the user picked, or the one the default-quality setting asks for.
     *
     * <p>Three layers, in order: this session's tap on a rung, then screen 10's setting resolved
     * against this video's own ladder, then nothing. "Nothing" is only reachable under "Always
     * ask", and it is deliberate — see {@link #preselect}.
     */
    @Nullable
    public MediaVariant selectedFor(MediaItem item) {
        String url = selection.get(item.groupKey);
        if (url != null) {
            // Looked for in the ladder the grid is actually showing, not in every variant the
            // engine holds. variantFor searches all of them, including ones qualities() leaves out
            // — an unlabelled duplicate, or a stream dropped once a real file turned up. A pick
            // resolved to one of those ticks no card on screen and downloads something the viewer
            // never saw, which reads as the tap having done nothing.
            for (MediaVariant rung : item.qualities()) {
                if (rung.url.equals(url)) return rung;
            }
            // The ladder changed under the pick — a probe finished, a better file arrived. Dropped
            // rather than kept, so the next line can choose something that exists.
            selection.remove(item.groupKey);
        }
        if (preselect == null) {
            // "Always ask" of a video with one rung is not a question — the same rule the batch
            // list follows. Anything with a real ladder waits to be asked.
            List<MediaVariant> rungs = new ArrayList<>();
            for (MediaVariant v : item.qualities()) {
                if (v.kind.downloadable()) rungs.add(v);
            }
            return rungs.size() == 1 ? rungs.get(0) : null;
        }

        MediaVariant wanted = preselect.resolve(item);
        if (wanted != null) return wanted;
        // The setting could not be answered — audio-only asked of a silent video. Fall back to
        // the best there is rather than leaving the card looking unsupported.
        MediaVariant best = item.bestDownloadable();
        if (best != null) return best;
        List<MediaVariant> all = item.variants();
        return all.isEmpty() ? null : all.get(0);
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PageHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.page_media, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder h, int position) {
        MediaItem item = items.get(position);
        MediaVariant selected = selectedFor(item);

        sizePreview(h, item);
        Thumbnails.load(h.thumb, item, h.thumbShimmer);

        h.title.setText(item.displayTitle());
        h.meta.setText(item.subtitle());

        h.badgePlaying.setVisibility(item.current ? View.VISIBLE : View.GONE);
        if (item.durationMs > 0) {
            h.badgeDuration.setVisibility(View.VISIBLE);
            h.badgeDuration.setText(Formats.duration(item.durationMs));
        } else {
            h.badgeDuration.setVisibility(View.GONE);
        }

        bindQualities(h, item, selected);

        // When every rung is locked the button is not disabled, it is gone: the shield notice
        // bindQualities raised has already said why, and a dead button beneath it would be the
        // same message twice, once uselessly.
        boolean allLocked = h.protectedNotice != null
                && h.protectedNotice.getVisibility() == View.VISIBLE;
        h.download.setVisibility(allLocked ? View.GONE : View.VISIBLE);

        // Only worth offering when there is genuinely more than one thing to take, and never
        // beside a protected video that cannot join a batch anyway.
        boolean batchable = items.size() > 1 && downloadAll != null && !allLocked;
        h.downloadAll.setVisibility(batchable ? View.VISIBLE : View.GONE);
        if (batchable) {
            h.downloadAll.setText(h.itemView.getResources()
                    .getQuantityString(R.plurals.download_all, items.size(), items.size()));
            h.downloadAll.setOnClickListener(v -> downloadAll.downloadAll());
        }

        boolean downloadable = !item.drmProtected && selected != null && selected.kind.downloadable();
        h.download.setEnabled(downloadable);
        h.download.setText(downloadLabel(h, item, selected, downloadable));
        h.download.setOnClickListener(v -> {
            MediaVariant target = selectedFor(item);
            if (target != null && target.kind.downloadable() && !item.drmProtected) {
                callback.download(item, target);
            }
        });
    }

    /**
     * Shapes the preview to the video.
     *
     * <p>A fixed wide frame crops a reel down to a strip of its middle, which is usually the
     * least identifiable part of it. Portrait videos get a tall, narrow, centred frame instead;
     * everything else keeps the wide one.
     */
    private static void sizePreview(PageHolder h, MediaItem item) {
        // The frame itself never changes — same width, same height, portrait or landscape. It is
        // declared in the layout and nothing here touches it.
        //
        // Only how the picture sits inside it is decided here. A wide clip fills the frame and
        // is cropped at the edges, which costs nothing. A portrait reel cropped to a wide frame
        // would lose almost all of itself, so it is fitted inside instead — the whole video,
        // letterboxed, in a frame the same size as every other.
        h.thumb.setScaleType(item.portrait()
                ? ImageView.ScaleType.FIT_CENTER
                : ImageView.ScaleType.CENTER_CROP);
    }

    private String downloadLabel(PageHolder h, MediaItem item, MediaVariant selected,
                                 boolean downloadable) {
        if (item.drmProtected) return h.itemView.getContext().getString(R.string.protected_content);
        // Nothing chosen yet under "Always ask" is not the same as nothing to choose. The button
        // says which it is, so a viewer who set that preference is told what it is waiting for
        // rather than being shown a video labelled unsupported.
        if (selected == null && item.bestDownloadable() != null) {
            return h.itemView.getContext().getString(R.string.pick_a_quality);
        }
        if (!downloadable) return h.itemView.getContext().getString(R.string.unsupported);

        String base = h.itemView.getContext().getString(R.string.download);
        long size = selected == null ? 0 : selected.sizeFor(item.durationMs);
        if (size > 0) return base + " • " + Formats.bytes(size);
        return base;
    }

    private void bindQualities(PageHolder h, MediaItem item, MediaVariant selected) {
        h.qualities.removeAllViews();
        // The grid, so the sheet's own list rather than the engine's — see MediaItem.qualities().
        List<MediaVariant> variants = item.qualities();
        if (item.drmProtected || variants.isEmpty()) {
            h.qualities.setVisibility(View.GONE);
            h.qualityLabel.setVisibility(View.GONE);
            // Protected is a state to explain, not an empty space. The notice replaces the
            // ladder and, in bindDownload, the button as well.
            if (h.protectedNotice != null) {
                h.protectedNotice.setVisibility(item.drmProtected ? View.VISIBLE : View.GONE);
            }
            return;
        }
        h.qualities.setVisibility(View.VISIBLE);
        h.qualityLabel.setVisibility(View.VISIBLE);

        Context context = h.itemView.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        boolean anyOpenable = false;
        // Counted separately from the loop, so a rung the grid skips cannot leave a hole.
        int index = 0;

        for (MediaVariant variant : variants) {
            View card = inflater.inflate(R.layout.item_quality, h.qualities, false);

            TextView name = card.findViewById(R.id.qualityName);
            TextView meta = card.findViewById(R.id.qualityMeta);
            // The card, not the root. The root is a frame that only exists to let the tick
            // overhang the corner; the card inside it is clickable, so it is the card that
            // receives the tap — a listener on the frame never fires, which is what stopped
            // quality switching from working at all.
            MaterialCardView tappable = card.findViewById(R.id.qualityCard);

            boolean openable = variant.kind.downloadable();
            anyOpenable |= openable;

            name.setText(variant.qualityName());
            String detail = variant.qualityMeta(item.durationMs);
            meta.setText(detail);
            // A size still being probed leaves the line out rather than blocking the rung —
            // the card stays tappable while the number is on its way.
            meta.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);

            boolean chosen = variant == selected;
            card.findViewById(R.id.qualityCheck)
                    .setVisibility(chosen ? View.VISIBLE : View.GONE);
            styleCard(tappable, chosen, openable);

            if (openable) {
                tappable.setOnClickListener(v -> {
                    selection.put(item.groupKey, variant.url);
                    // By group key, not by the holder's position. A holder that has just been
                    // rebound reports NO_POSITION for a frame, and a change notification sent to
                    // -1 is a notification nobody receives — the tick then stays where it was and
                    // the tap looks ignored.
                    int at = indexOf(item.groupKey);
                    if (at >= 0) notifyItemChanged(at);
                });
            } else {
                // Not merely unstyled — genuinely inert, so a locked rung cannot be selected
                // by a determined tap.
                tappable.setOnClickListener(null);
                tappable.setClickable(false);
            }

            // Three to a row, filling the width equally. A short final row is left-aligned
            // under the one above rather than centred: the rungs descend in quality from the
            // top left, and centring the remainder breaks that reading order.
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(index % QUALITY_COLUMNS, 1, 1f);
            params.rowSpec = GridLayout.spec(index / QUALITY_COLUMNS);
            card.setLayoutParams(params);
            index++;

            h.qualities.addView(card);
        }

        if (h.protectedNotice != null) {
            h.protectedNotice.setVisibility(anyOpenable ? View.GONE : View.VISIBLE);
        }
    }

    /**
     * Selection reads three ways at once: the corner tick, a tinted fill, and a border in the
     * brand colour. The stroke <em>width</em> deliberately never changes — only its colour —
     * because growing it nudges the card's contents by a pixel as you tap along the row.
     */
    private static void styleCard(MaterialCardView card, boolean selected, boolean enabled) {
        Context context = card.getContext();
        int accent = ContextCompat.getColor(context, R.color.ds_accent);
        int accentSoft = ContextCompat.getColor(context, R.color.ds_accent_soft);
        int surfaceAlt = ContextCompat.getColor(context, R.color.ds_surface_alt);

        // Selected reads three ways — a fill, a border and the corner tick — because the design
        // requires selection to be legible without relying on any single one of them.
        //
        // An unselected rung carries no border at all, only the surface-alt fill. The stroke
        // width is set on both so the card's contents never shift by a pixel as the selection
        // moves along the row.
        float density = context.getResources().getDisplayMetrics().density;
        card.setStrokeWidth(Math.round(1.5f * density));
        card.setStrokeColor(selected ? accent : Color.TRANSPARENT);
        card.setCardBackgroundColor(selected ? accentSoft : surfaceAlt);
        card.setEnabled(enabled);
        // Locked rungs stay readable rather than fading to nothing: the viewer still needs to
        // know which resolutions exist, only that none of them can be had.
        card.setAlpha(enabled ? 1f : 0.55f);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView meta;
        final View thumbCard;
        final View thumbShimmer;
        final TextView badgePlaying;
        final TextView badgeDuration;
        final TextView qualityLabel;
        /** A row now, not a grid — the rungs read as a ladder along one line. */
        final GridLayout qualities;
        final View protectedNotice;
        final TextView downloadAll;
        final MaterialButton download;

        PageHolder(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            thumbCard = v.findViewById(R.id.thumbCard);
            thumbShimmer = v.findViewById(R.id.thumbShimmer);
            title = v.findViewById(R.id.title);
            meta = v.findViewById(R.id.meta);
            badgePlaying = v.findViewById(R.id.badgePlaying);
            badgeDuration = v.findViewById(R.id.badgeDuration);
            qualityLabel = v.findViewById(R.id.qualityLabel);
            qualities = v.findViewById(R.id.qualities);
            protectedNotice = v.findViewById(R.id.protectedNotice);
            downloadAll = v.findViewById(R.id.btnDownloadAll);
            download = v.findViewById(R.id.btnDownload);
        }
    }
}
