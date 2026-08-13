package com.ms.webview.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.ui.home.SearchHistory;
import com.ms.webview.ui.home.Shortcuts;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Where the browser has been, broken up by the day it went there.
 *
 * <p>The days are rows in the same list rather than a separate list of sections, which is what
 * lets one adapter serve both the history screen and a search of it: the same grouping falls out
 * of whatever set of visits it is handed.
 */
public class SearchHistoryAdapter
        extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ENTRY = 1;

    public interface Listener {
        void onOpenHistory(SearchHistory.Entry entry);

        void onRemoveHistory(SearchHistory.Entry entry);
    }

    /** Either a {@link String} day heading or a {@link SearchHistory.Entry}. */
    private final List<Object> rows = new ArrayList<>();
    private final Listener listener;
    private final Context context;

    public SearchHistoryAdapter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Takes visits newest first and lays a heading before each change of day.
     *
     * <p>Grouping here rather than at the call sites because it is the same grouping every time,
     * and because the adapter is the only thing that knows a heading is a row.
     */
    public void submit(List<SearchHistory.Entry> entries) {
        rows.clear();
        String currentDay = null;

        if (entries != null) {
            for (SearchHistory.Entry entry : entries) {
                String day = dayLabel(entry.visitedAt);
                if (!day.equals(currentDay)) {
                    rows.add(day);
                    currentDay = day;
                }
                rows.add(entry);
            }
        }
        notifyDataSetChanged();
    }

    /**
     * "Today", "Yesterday", then the date itself.
     *
     * <p>Named days for the two that have names, because those are the ones a viewer thinks in.
     * Beyond that a date says more than "6 days ago" does, and does not have to be counted back
     * from anything.
     */
    private String dayLabel(long timestamp) {
        if (timestamp <= 0) return context.getString(R.string.earlier);

        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(timestamp);

        Calendar today = Calendar.getInstance();
        String date = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(new Date(timestamp));

        if (sameDay(then, today)) return context.getString(R.string.today_on, date);

        today.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(then, today)) return context.getString(R.string.yesterday_on, date);

        return date;
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? TYPE_HEADER : TYPE_ENTRY;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(
                    inflater.inflate(R.layout.item_history_header, parent, false));
        }
        return new HistoryHolder(inflater.inflate(R.layout.item_history, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);

        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).label.setText((String) row);
            return;
        }

        SearchHistory.Entry entry = (SearchHistory.Entry) row;
        HistoryHolder h = (HistoryHolder) holder;

        h.title.setText(entry.label());
        h.url.setText(Formats.hostOf(entry.url));

        // The site's own mark where the app carries one, so a list of visits is scannable by
        // shape rather than by reading every line.
        int brand = Shortcuts.iconForUrl(entry.url);
        h.icon.setImageResource(brand != 0 ? brand : R.drawable.ic_globe);

        h.itemView.setOnClickListener(v -> listener.onOpenHistory(entry));
        h.remove.setOnClickListener(v -> listener.onRemoveHistory(entry));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView label;

        HeaderHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.headerLabel);
        }
    }

    static class HistoryHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView url;
        final ImageButton remove;

        HistoryHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.historyIcon);
            title = itemView.findViewById(R.id.historyTitle);
            url = itemView.findViewById(R.id.historyUrl);
            remove = itemView.findViewById(R.id.historyRemove);
        }
    }
}
