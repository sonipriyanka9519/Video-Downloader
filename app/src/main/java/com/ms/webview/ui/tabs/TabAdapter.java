package com.ms.webview.ui.tabs;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.ms.webview.R;
import com.ms.webview.core.Formats;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** The tab switcher's grid: one card per open tab, the one in front outlined. */
public class TabAdapter extends RecyclerView.Adapter<TabAdapter.TabHolder> {

    public interface Listener {
        void onOpenTab(Tab tab);

        void onCloseTab(Tab tab);
    }

    private final List<Tab> tabs = new ArrayList<>();
    private final Listener listener;
    @Nullable
    private String currentId;

    public TabAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Tab> incoming, @Nullable String currentId) {
        tabs.clear();
        if (incoming != null) tabs.addAll(incoming);
        this.currentId = currentId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TabHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new TabHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_tab, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull TabHolder h, int position) {
        Tab tab = tabs.get(position);

        // The active tab wears a 2dp accent border; every other a 1dp line. Set as the card's
        // stroke rather than by swapping a background, so the preview inside is never nudged by
        // a different drawable's insets as the selection moves.
        //
        // In the private grid the active border is ink, not accent: accent is the colour of a
        // download in this app, and a red border around a private tab reads as one.
        boolean active = tab.id.equals(currentId);
        Context context = h.itemView.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        h.card.setStrokeWidth(Math.round((active ? 2f : 1f) * density));
        h.card.setStrokeColor(ContextCompat.getColor(context,
                active ? (tab.incognito ? R.color.ds_ink : R.color.ds_accent) : R.color.ds_line));

        if (tab.incognito) {
            // Numbered, never named. A page title on a private card is the one thing the mode
            // promises not to show — and the number is enough to tell two of them apart.
            h.title.setText(context.getString(R.string.private_tab_numbered, position + 1));
            h.icon.setImageResource(R.drawable.ic_eye_off);
            // No snapshot exists and none is wanted: the preview area stays a plain block.
            h.preview.setImageDrawable(null);
            h.preview.setBackgroundColor(ContextCompat.getColor(context, R.color.ds_surface_alt));
        } else {
            // The page's own title where it has one, then the host, then the fact that it is
            // empty. Never the raw address: a signed CDN link fills the line and says nothing.
            String label = !TextUtils.isEmpty(tab.title) ? tab.title
                    : tab.isBlank() ? context.getString(R.string.new_tab)
                            : Formats.hostOf(tab.url);
            h.title.setText(label);
            h.icon.setImageResource(R.drawable.ic_globe);
            bindPreview(h, tab);
        }

        h.card.setOnClickListener(v -> listener.onOpenTab(tab));
        h.close.setOnClickListener(v -> listener.onCloseTab(tab));
    }

    /**
     * Decoded straight off the card rather than through a cache: the pictures are small, there
     * are a dozen at most, and the switcher is open for a second at a time.
     */
    private static void bindPreview(TabHolder h, Tab tab) {
        h.preview.setImageDrawable(null);
        if (TextUtils.isEmpty(tab.previewPath)) return;

        File file = new File(tab.previewPath);
        if (!file.exists()) return;
        h.preview.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));
    }

    @Override
    public int getItemCount() {
        return tabs.size();
    }

    static class TabHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final ImageView preview;
        final ImageView icon;
        final ImageButton close;
        final TextView title;

        TabHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.tabCard);
            preview = itemView.findViewById(R.id.tabPreview);
            icon = itemView.findViewById(R.id.tabIcon);
            close = itemView.findViewById(R.id.tabClose);
            title = itemView.findViewById(R.id.tabTitle);
        }
    }
}
