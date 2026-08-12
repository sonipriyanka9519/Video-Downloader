package com.ms.webview.ui;

import android.content.Context;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.detect.MediaItem;
import com.ms.webview.detect.MediaVariant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One full page per detected video, swiped horizontally. */
public class MediaPagerAdapter extends RecyclerView.Adapter<MediaPagerAdapter.PageHolder> {

    private static final int MAX_QUALITY_COLUMNS = 3;

    public interface OnDownload {
        void download(MediaItem item, MediaVariant variant);
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

    public MediaPagerAdapter(OnDownload callback) {
        this.callback = callback;
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

    /** The variant the user picked, or the best downloadable one. */
    @Nullable
    public MediaVariant selectedFor(MediaItem item) {
        String url = selection.get(item.groupKey);
        if (url != null) {
            MediaVariant chosen = item.variantFor(url);
            if (chosen != null) return chosen;
        }
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
        boolean portrait = item.portrait();

        // Scalable units, same as the layouts, so the preview keeps its proportions on a tablet.
        int height = h.itemView.getResources().getDimensionPixelSize(
                portrait ? com.intuit.sdp.R.dimen._126sdp : com.intuit.sdp.R.dimen._96sdp);
        int width = portrait
                ? Math.round(height * 9f / 16f)
                : ViewGroup.LayoutParams.MATCH_PARENT;

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) h.thumbCard.getLayoutParams();
        if (params.height != height || params.width != width) {
            params.height = height;
            params.width = width;
            params.gravity = Gravity.CENTER_HORIZONTAL;
            h.thumbCard.setLayoutParams(params);
        }
    }

    private String downloadLabel(PageHolder h, MediaItem item, MediaVariant selected,
                                 boolean downloadable) {
        if (item.drmProtected) return h.itemView.getContext().getString(R.string.protected_content);
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
            return;
        }
        h.qualities.setVisibility(View.VISIBLE);
        h.qualityLabel.setVisibility(View.VISIBLE);

        Context context = h.itemView.getContext();
        int total = variants.size();
        // Three across, or fewer when there are fewer — a lone quality then fills the row
        // instead of sitting in a third of it.
        int columns = Math.min(MAX_QUALITY_COLUMNS, total);
        int rows = (total + columns - 1) / columns;
        h.qualities.setColumnCount(columns);

        int gap = context.getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._5sdp);
        LayoutInflater inflater = LayoutInflater.from(context);

        for (int i = 0; i < total; i++) {
            MediaVariant variant = variants.get(i);
            View card = inflater.inflate(R.layout.item_quality, h.qualities, false);

            TextView name = card.findViewById(R.id.qualityName);
            TextView meta = card.findViewById(R.id.qualityMeta);
            name.setText(variant.qualityName());
            String detail = variant.qualityMeta(item.durationMs);
            meta.setText(detail);
            meta.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);

            boolean chosen = variant == selected;
            card.findViewById(R.id.qualityCheck)
                    .setVisibility(chosen ? View.VISIBLE : View.GONE);
            styleCard((MaterialCardView) card, chosen, variant.kind.downloadable());

            int row = i / columns;
            int indexInRow = i % columns;
            // A short final row is centred rather than left-hanging, so five qualities read as
            // a block instead of three-then-two-stuck-to-the-left.
            int itemsInRow = (row == rows - 1) ? total - row * columns : columns;
            int column = ((columns - itemsInRow) / 2) + indexInRow;

            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.height = GridLayout.LayoutParams.WRAP_CONTENT;
            params.columnSpec = GridLayout.spec(column, 1, 1f);
            params.rowSpec = GridLayout.spec(row);
            params.setMargins(gap / 2, gap / 2, gap / 2, gap / 2);
            card.setLayoutParams(params);

            if (variant.kind.downloadable()) {
                card.setOnClickListener(v -> {
                    selection.put(item.groupKey, variant.url);
                    notifyItemChanged(h.getBindingAdapterPosition());
                });
            }
            h.qualities.addView(card);
        }
    }

    /**
     * Selection reads three ways at once: the corner tick, a tinted fill, and a border in the
     * brand colour. The stroke <em>width</em> deliberately never changes — only its colour —
     * because growing it nudges the card's contents by a pixel as you tap along the row.
     */
    private static void styleCard(MaterialCardView card, boolean selected, boolean enabled) {
        // colorPrimary is declared by appcompat; Material reuses it rather than redeclaring it,
        // so it is not in com.google.android.material.R.attr the way the others below are.
        int primary = MaterialColors.getColor(card, androidx.appcompat.R.attr.colorPrimary);
        int outline = MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorOutlineVariant);
        int container = MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorPrimaryContainer);
        int surface = MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorSurface);

        card.setStrokeWidth(Math.round(card.getResources().getDisplayMetrics().density));
        card.setStrokeColor(selected ? primary : outline);
        card.setCardBackgroundColor(selected ? container : surface);
        card.setEnabled(enabled);
        card.setAlpha(enabled ? 1f : 0.45f);
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
        final GridLayout qualities;
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
            download = v.findViewById(R.id.btnDownload);
        }
    }
}
