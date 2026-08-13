package com.ms.webview.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;

import java.util.ArrayList;
import java.util.List;

/**
 * What has been searched for before, offered under a focused address bar.
 *
 * <p>Searches rather than visited pages. The two were the same list once and it read badly: a
 * column of addresses is not an answer to "what was I looking for", and it pushed the thing the
 * viewer wanted off the bottom of the screen. The pages have a screen of their own now.
 */
public class SearchQueryAdapter extends RecyclerView.Adapter<SearchQueryAdapter.QueryHolder> {

    public interface Listener {
        /** Run it. */
        void onRunQuery(String query);

        /** Put it in the box so it can be finished off rather than run as it stands. */
        void onEditQuery(String query);
    }

    private final List<String> items = new ArrayList<>();
    private final Listener listener;

    public SearchQueryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<String> queries) {
        items.clear();
        if (queries != null) items.addAll(queries);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public QueryHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new QueryHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_search_query, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull QueryHolder h, int position) {
        String query = items.get(position);
        h.text.setText(query);
        h.itemView.setOnClickListener(v -> listener.onRunQuery(query));
        h.insert.setOnClickListener(v -> listener.onEditQuery(query));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class QueryHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final ImageButton insert;

        QueryHolder(@NonNull View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.queryText);
            insert = itemView.findViewById(R.id.queryInsert);
        }
    }
}
