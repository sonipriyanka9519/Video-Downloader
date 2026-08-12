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
        this.listener = listener;
        this.withAddTile = withAddTile;
        this.horizontal = horizontal;
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
                .inflate(R.layout.item_shortcut, parent, false);
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
            h.itemView.setOnClickListener(v -> listener.onAdd());
            h.itemView.setOnLongClickListener(null);
            return;
        }

        Shortcut shortcut = items.get(position);
        h.icon.setImageResource(shortcut.icon);
        h.label.setText(shortcut.label);
        h.itemView.setOnClickListener(v -> listener.onOpen(shortcut));
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

        Holder(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.shortcutIcon);
            label = v.findViewById(R.id.shortcutLabel);
        }
    }
}
