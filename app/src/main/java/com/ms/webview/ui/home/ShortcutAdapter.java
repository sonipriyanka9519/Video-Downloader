package com.ms.webview.ui.home;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;

import java.util.ArrayList;
import java.util.List;

/**
 * The home grid, and the same grid again inside the Add sheet.
 *
 * <p>Icons are bundled vectors, so the grid is complete the moment it is drawn and looks the
 * same offline.
 */
public class ShortcutAdapter extends RecyclerView.Adapter<ShortcutAdapter.Holder> {

    private static final int TYPE_SITE = 0;
    /** The trailing tile, which opens the picker rather than a site. */
    private static final int TYPE_ADD = 1;

    public interface Listener {
        void onOpen(Shortcut shortcut);

        void onAdd();

        void onRemove(Shortcut shortcut);
    }

    /** A tile's width in a row, where it cannot take a share of the screen the way a grid does. */
    private static final int ROW_TILE_WIDTH = com.intuit.sdp.R.dimen._62sdp;

    private final List<Shortcut> items = new ArrayList<>();
    private final Listener listener;
    private final boolean withAddTile;
    private final boolean horizontal;
    /**
     * Which tile to inflate.
     *
     * <p>Parameterised because this adapter serves two grids that are migrating at different
     * times: Home is rebuilt against screen 01, while the Add sheet still wears the MVP tile.
     * Hard-coding the new layout here would silently restyle a sheet nobody has redesigned yet.
     */
    private final int layoutRes;
    /** Edit mode: every site tile grows a remove badge. The Add tile never does. */
    private boolean editing;

    public ShortcutAdapter(Listener listener, boolean withAddTile) {
        this(listener, withAddTile, false);
    }

    /**
     * @param horizontal true when the tiles are laid out in a row rather than a grid.
     *                   The tile is written for a grid, where the layout manager divides the
     *                   width between the columns and a {@code match_parent} tile fills its
     *                   share. A row manager gives a tile whatever it asks for, so the same
     *                   {@code match_parent} takes the entire list and exactly one icon is
     *                   visible at a time. In a row the tile has to name its own width.
     */
    public ShortcutAdapter(Listener listener, boolean withAddTile, boolean horizontal) {
        // The same tile Home uses. It used to default to the MVP layout, which meant the shortcut
        // picker drew its tiles in the old type and colours while the grid behind it drew them in
        // the new ones - the same list, twice, in two designs.
        this(listener, withAddTile, horizontal, R.layout.item_ds_shortcut);
    }

    public ShortcutAdapter(Listener listener, boolean withAddTile, boolean horizontal,
                           int layoutRes) {
        this.listener = listener;
        this.withAddTile = withAddTile;
        this.horizontal = horizontal;
        this.layoutRes = layoutRes;
    }

    /** Turns the remove badges on and off. No-op on a tile that has none. */
    public void setEditing(boolean editing) {
        if (this.editing == editing) return;
        this.editing = editing;
        notifyDataSetChanged();
    }

    public boolean isEditing() {
        return editing;
    }

    public void submit(List<Shortcut> shortcuts) {
        items.clear();
        if (shortcuts != null) items.addAll(shortcuts);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return withAddTile && position == items.size() ? TYPE_ADD : TYPE_SITE;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(layoutRes, parent, false);
        if (horizontal) {
            ViewGroup.LayoutParams params = view.getLayoutParams();
            params.width = parent.getResources().getDimensionPixelSize(ROW_TILE_WIDTH);
            view.setLayoutParams(params);
        }
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        if (getItemViewType(position) == TYPE_ADD) {
            h.icon.setImageResource(R.drawable.bg_add_tile);
            h.label.setText(R.string.add_shortcut);
            // The Add cell is a slot, not a site: nothing to remove, and it stays tappable
            // while editing so a site can be added without leaving the mode first.
            if (h.remove != null) h.remove.setVisibility(View.GONE);
            h.itemView.setOnClickListener(v -> listener.onAdd());
            h.itemView.setOnLongClickListener(null);
            return;
        }

        Shortcut shortcut = items.get(position);
        h.icon.setImageResource(shortcut.icon);
        h.label.setText(shortcut.label);

        if (h.remove != null) {
            h.remove.setVisibility(editing ? View.VISIBLE : View.GONE);
            h.remove.setOnClickListener(editing ? v -> listener.onRemove(shortcut) : null);
        }

        // While editing, the tile itself stops opening the site — the whole point of the mode is
        // that a tap is about the tile rather than about going somewhere.
        h.itemView.setOnClickListener(editing ? null : v -> listener.onOpen(shortcut));
        h.itemView.setClickable(!editing);
        // Long-press remains the shortcut into removal, and is also what enters edit mode where
        // a badge exists to enter it with.
        h.itemView.setOnLongClickListener(v -> {
            listener.onRemove(shortcut);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size() + (withAddTile ? 1 : 0);
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView label;
        /** Null on the MVP tile, which has no badge. Every use of it is guarded. */
        final ImageView remove;

        Holder(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.shortcutIcon);
            label = v.findViewById(R.id.shortcutLabel);
            remove = v.findViewById(R.id.shortcutRemove);
        }
    }
}
