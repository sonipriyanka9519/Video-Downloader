package com.ms.webview.ui.tabs;

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
import androidx.recyclerview.widget.RecyclerView;

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

        boolean active = tab.id.equals(currentId);
        h.card.setBackgroundResource(active
                ? R.drawable.bg_tab_card_active : R.drawable.bg_tab_card);

        // The page's own title where it has one, then the host, then the fact that it is empty.
        // Never the raw address: a signed CDN link fills the line and says nothing.
        String label = !TextUtils.isEmpty(tab.title) ? tab.title
                : tab.isBlank() ? h.itemView.getContext().getString(R.string.new_tab)
                        : Formats.hostOf(tab.url);
        h.title.setText(label);

        bindPreview(h, tab);

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
        final View card;
        final ImageView preview;
        final ImageButton close;
        final TextView title;

        TabHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.tabCard);
            preview = itemView.findViewById(R.id.tabPreview);
            close = itemView.findViewById(R.id.tabClose);
            title = itemView.findViewById(R.id.tabTitle);
        }
    }
}
