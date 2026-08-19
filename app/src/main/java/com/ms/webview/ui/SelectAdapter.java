package com.ms.webview.ui;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.detect.MediaItem;
import com.ms.webview.detect.MediaVariant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The batch checklist — screen 03, panel C.
 *
 * <p>Every row starts ticked. The viewer asked to download all of them; the checkboxes are there
 * to take one or two back out, not to make them choose the set from scratch.
 */
public class SelectAdapter extends RecyclerView.Adapter<SelectAdapter.Holder> {

    public interface Listener {
        void onSelectionChanged();
    }

    /** Raised by a row's quality pill; the sheet owns the picker, this only knows the answer. */
    public interface Picker {
        void pickQuality(MediaItem item);
    }

    private final List<MediaItem> items = new ArrayList<>();
    /** Group keys, not positions: the registry re-sorts the list under us constantly. */
    private final Set<String> chosen = new HashSet<>();
    /**
     * One video's own quality, against the shared choice — group key to variant url.
     *
     * <p>Keyed and valued by identity rather than by position or index, for the same reason the
     * ticks are: the registry republishes this list constantly and a row's number means nothing
     * between one refresh and the next.
     *
     * <p>Cleared when a chip is tapped. A chip is a decision about the whole set, and leaving four
     * private exceptions standing under it would make the chip a lie.
     */
    private final Map<String, String> perVideo = new HashMap<>();
    private final Listener listener;
    @Nullable
    private Picker picker;
    /**
     * Null means nothing has been chosen yet, which is what screen 10's "Always ask" asks for.
     * Rows still tick, but no size can be quoted and the button waits — see {@link #hasQuality()}.
     */
    @Nullable
    private BatchQuality quality = BatchQuality.BEST_UNDER_720;

    public SelectAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setPicker(@Nullable Picker picker) {
        this.picker = picker;
    }

    public void submit(List<MediaItem> incoming) {
        items.clear();
        chosen.clear();
        perVideo.clear();
        if (incoming != null) {
            for (MediaItem item : incoming) {
                // A protected video cannot join a batch, so it is not offered one.
                if (item.drmProtected || item.bestDownloadable() == null) continue;
                items.add(item);
                chosen.add(item.groupKey);
            }
        }
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    public void setQuality(@Nullable BatchQuality quality) {
        this.quality = quality;
        // One decision for the set means one decision for the set — see perVideo.
        perVideo.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    @Nullable
    public BatchQuality quality() {
        return quality;
    }

    /** Sets one video's quality, against whatever the chip row says — or with nothing said at all. */
    public void setQualityFor(MediaItem item, String url) {
        perVideo.put(item.groupKey, url);
        // Choosing a size for something is wanting it. Ticking it separately would be asking the
        // same question twice.
        chosen.add(item.groupKey);
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    /**
     * The rung this video will actually be taken at.
     *
     * <p>Its own choice first, then the shared chip, then nothing — and nothing is reachable, under
     * "Always ask", which is the whole point of it. A row with no rung yet is not a row that cannot
     * be taken; it is one nobody has decided about.
     */
    @Nullable
    public MediaVariant rungFor(MediaItem item) {
        String url = perVideo.get(item.groupKey);
        if (url != null) {
            MediaVariant own = item.variantFor(url);
            if (own != null) return own;
        }
        if (quality != null) return quality.resolve(item);

        // "Always ask" of a video with one rung is not a question. Left unanswered it would be a
        // row asking to be tapped, offering a list of one, and refusing to download until it was.
        List<MediaVariant> only = pickable(item);
        return only.size() == 1 ? only.get(0) : null;
    }

    /**
     * Whether this row can still be taken — either it has a rung, or it is waiting for one.
     *
     * <p>The distinction that matters for the tick and the dimming: "no quality chosen yet" and
     * "this quality does not exist for this video" look the same in the data and mean opposite
     * things to somebody looking at the row.
     */
    private boolean offerable(MediaItem item) {
        if (rungFor(item) != null) return true;
        return quality == null && !perVideo.containsKey(item.groupKey);
    }

    public void selectAll() {
        for (MediaItem item : items) chosen.add(item.groupKey);
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    /** The other half of the select-all checkbox: a second tap on a full set empties it. */
    public void selectNone() {
        chosen.clear();
        notifyDataSetChanged();
        listener.onSelectionChanged();
    }

    /** Whether every row is ticked, which is what the select-all checkbox shows. */
    public boolean allTicked() {
        return !items.isEmpty() && tickedCount() == items.size();
    }

    public int total() {
        return items.size();
    }

    /**
     * What the header counts: rows that are ticked and could still be taken.
     *
     * <p>Ticks, not resolved downloads — a row waiting for its quality is one the viewer has said
     * yes to, and "0 of 4" over four visibly ticked rows would be the screen contradicting itself.
     * What the button can actually queue is {@link #readyCount()}, which is a different number and
     * is allowed to be.
     */
    public int selectedCount() {
        return tickedCount();
    }

    /** Ticked <em>and</em> settled on a rung — what the download button counts and totals. */
    public int readyCount() {
        return resolvedItems().size();
    }

    private int tickedCount() {
        int n = 0;
        for (MediaItem item : items) {
            if (chosen.contains(item.groupKey) && offerable(item)) n++;
        }
        return n;
    }

    /**
     * The videos that are ticked <em>and</em> have a rung answering the current quality choice.
     *
     * <p>The two are not the same. Ask a set for Audio and the ones without a sound track have
     * nothing to give; they stay in the list, ticked, but contribute no download and no bytes.
     */
    public List<MediaItem> resolvedItems() {
        List<MediaItem> out = new ArrayList<>();
        for (MediaItem item : items) {
            if (chosen.contains(item.groupKey) && rungFor(item) != null) out.add(item);
        }
        return out;
    }

    private List<MediaVariant> resolved() {
        List<MediaVariant> out = new ArrayList<>();
        for (MediaItem item : items) {
            if (!chosen.contains(item.groupKey)) continue;
            MediaVariant v = rungFor(item);
            if (v != null) out.add(v);
        }
        return out;
    }

    /** What the batch will weigh, for the button. Zero when nothing has a size yet. */
    public long totalBytes() {
        long sum = 0;
        for (MediaItem item : items) {
            if (!chosen.contains(item.groupKey)) continue;
            MediaVariant v = rungFor(item);
            if (v != null) sum += v.sizeFor(item.durationMs);
        }
        return sum;
    }

    /**
     * What everything on the page weighs, ticked or not — the figure beside select-all.
     *
     * <p>Deliberately not the selection's total: that one is on the button, and two numbers that
     * moved together would be one number printed twice. This one answers "how much is here".
     */
    public long allBytes() {
        long sum = 0;
        for (MediaItem item : items) {
            MediaVariant v = rungFor(item);
            if (v != null) sum += v.sizeFor(item.durationMs);
        }
        return sum;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_select_video, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        MediaItem item = items.get(position);
        MediaVariant variant = rungFor(item);
        // Nothing chosen yet is not the same as nothing available: the row stays live and tickable
        // and says what it is waiting for, rather than claiming the video cannot be taken.
        boolean live = offerable(item);
        boolean waiting = live && variant == null;

        h.title.setText(item.displayTitle());
        Thumbnails.load(h.thumb, item);

        // The video the page is actually playing, tinted — the same fact the pager states with a
        // NOW PLAYING badge, said the way a row can say it.
        h.itemView.setBackgroundColor(item.current
                ? ContextCompat.getColor(h.itemView.getContext(), R.color.ds_accent_soft)
                : Color.TRANSPARENT);

        if (waiting) {
            h.meta.setText(R.string.pick_a_quality);
        } else if (variant == null) {
            // Honest about why this one will not be taken, rather than silently dropping it.
            h.meta.setText(R.string.batch_unavailable);
        } else {
            long size = variant.sizeFor(item.durationMs);
            h.meta.setText(size > 0
                    ? variant.qualityName() + " · " + Formats.bytes(size)
                    : variant.qualityName());
        }

        // The pill. Every bind sets its label, its listener and whether it is there at all — a row
        // that kept the last one's rung after recycling would offer a size this video may not have.
        List<MediaVariant> rungs = pickable(item);
        boolean choosable = picker != null && rungs.size() > 1;
        h.quality.setVisibility(choosable ? View.VISIBLE : View.GONE);
        if (choosable) {
            h.quality.setText(variant != null
                    ? variant.qualityName() : h.quality.getContext().getString(R.string.choose));
            h.quality.setOnClickListener(v -> picker.pickQuality(item));
        } else {
            h.quality.setOnClickListener(null);
        }

        boolean ticked = chosen.contains(item.groupKey);
        h.check.setChecked(ticked);
        h.check.setEnabled(live);
        h.itemView.setAlpha(live ? 1f : 0.5f);

        h.itemView.setOnClickListener(v -> {
            if (!live) return;
            if (!chosen.remove(item.groupKey)) chosen.add(item.groupKey);
            // By group key rather than by the holder's position, which is NO_POSITION for a frame
            // after a rebind — a change sent to -1 reaches nobody and the tick appears stuck.
            int at = items.indexOf(item);
            if (at >= 0) notifyItemChanged(at);
            listener.onSelectionChanged();
        });
    }

    /**
     * The rungs this video could be offered at — the ones that can actually be downloaded.
     *
     * <p>A single-rung video gets no pill: a control whose only option is the one already showing is
     * a control that does nothing when pressed.
     */
    public List<MediaVariant> pickable(MediaItem item) {
        List<MediaVariant> out = new ArrayList<>();
        if (item.drmProtected) return out;
        for (MediaVariant v : item.qualities()) {
            if (v.kind.downloadable()) out.add(v);
        }
        return out;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView meta;
        final TextView quality;
        final MaterialCheckBox check;

        Holder(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.selectThumb);
            title = v.findViewById(R.id.selectTitle);
            meta = v.findViewById(R.id.selectMeta);
            quality = v.findViewById(R.id.selectQuality);
            check = v.findViewById(R.id.selectCheck);
        }
    }
}
