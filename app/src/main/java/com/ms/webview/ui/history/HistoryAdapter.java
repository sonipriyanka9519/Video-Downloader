package com.ms.webview.ui.history;

import android.content.Context;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StyleSpan;
import android.graphics.Typeface;
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
import com.ms.webview.ui.home.SearchHistory;
import com.ms.webview.ui.home.Shortcuts;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The history list — screen 12, panels A and B.
 *
 * <p>Three buckets, not one per date: <b>today</b>, <b>yesterday</b>, and everything else under
 * <b>earlier</b>. A heading per calendar day reads well for a week of history and turns into a page
 * of headings for a month of it, which is the state this list is actually in most of the time.
 *
 * <p>While searching, the headings are replaced by a single result count. Days are the wrong
 * grouping for a search — the answer to "where was that Pinterest page" is not "it was a Tuesday".
 *
 * <p>Its own adapter rather than the address bar's. The two rows have diverged: this one carries the
 * time of the visit, a ×N chip, and bold on what the query matched, none of which belong in a
 * suggestion list under a half-typed address.
 */
public class HistoryAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_SECTION = 0;
    private static final int TYPE_VISIT = 1;

    public interface Listener {
        void onOpenVisit(SearchHistory.Entry entry);

        void onRemoveVisit(SearchHistory.Entry entry);
    }

    /** Either a {@link String} heading or a {@link SearchHistory.Entry}. */
    private final List<Object> rows = new ArrayList<>();
    private final Context context;
    private final Listener listener;

    /** What is being searched for, so the rows can bold it. Empty when the whole list is showing. */
    private String query = "";

    public HistoryAdapter(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /**
     * Lays out the visits, grouped by day or counted as results.
     *
     * @param query what was typed, or empty for the whole list. Grouping and highlighting both
     *              follow from it, so they cannot disagree about which state the sheet is in.
     */
    public void submit(List<SearchHistory.Entry> entries, @Nullable String query) {
        this.query = query == null ? "" : query.trim();
        rows.clear();

        if (entries != null && !entries.isEmpty()) {
            if (this.query.isEmpty()) {
                groupByDay(entries);
            } else {
                rows.add(context.getResources().getQuantityString(
                        R.plurals.history_results, entries.size(), entries.size()));
                rows.addAll(entries);
            }
        }
        notifyDataSetChanged();
    }

    /**
     * Today, yesterday, earlier — each heading added only when a visit falls under it.
     *
     * <p>Today's carries the date beside it and the other two do not, which is the design's own
     * asymmetry and a sound one: "TODAY" alone is unambiguous, "EARLIER · 9 AUG" would be naming
     * one day out of the many in that bucket.
     */
    private void groupByDay(List<SearchHistory.Entry> entries) {
        int lastBucket = -1;
        for (SearchHistory.Entry entry : entries) {
            int bucket = bucketOf(entry.visitedAt);
            if (bucket != lastBucket) {
                rows.add(headingFor(bucket));
                lastBucket = bucket;
            }
            rows.add(entry);
        }
    }

    private static final int TODAY = 0;
    private static final int YESTERDAY = 1;
    private static final int EARLIER = 2;

    private int bucketOf(long timestamp) {
        // No timestamp means a visit recorded before they were kept. It is old; earlier is where
        // old things go, and it is the only bucket that does not claim to know a date.
        if (timestamp <= 0) return EARLIER;

        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(timestamp);
        Calendar day = Calendar.getInstance();
        if (sameDay(then, day)) return TODAY;

        day.add(Calendar.DAY_OF_YEAR, -1);
        return sameDay(then, day) ? YESTERDAY : EARLIER;
    }

    private String headingFor(int bucket) {
        switch (bucket) {
            case TODAY:
                // Uppercased here because the date is generated, not written into strings.xml with
                // the rest of the heading.
                return context.getString(R.string.history_today,
                        Formats.dayMonth(System.currentTimeMillis()).toUpperCase(Locale.getDefault()));
            case YESTERDAY:
                return context.getString(R.string.history_yesterday);
            default:
                return context.getString(R.string.history_earlier);
        }
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof String ? TYPE_SECTION : TYPE_VISIT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_SECTION) {
            return new SectionHolder(
                    inflater.inflate(R.layout.item_history_section, parent, false));
        }
        return new VisitHolder(inflater.inflate(R.layout.item_history_visit, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof SectionHolder) {
            ((SectionHolder) holder).label.setText((String) row);
            return;
        }

        SearchHistory.Entry entry = (SearchHistory.Entry) row;
        VisitHolder h = (VisitHolder) holder;

        h.title.setText(highlight(entry.label()));
        h.host.setText(highlight(Formats.hostOf(entry.url)));

        // The site's own mark where the app carries one, so a long list is scannable by shape
        // rather than by reading every line.
        int brand = Shortcuts.iconForUrl(entry.url);
        h.icon.setImageResource(brand != 0 ? brand : R.drawable.ic_globe);

        // Reset both ways round: a recycled row that kept the last one's chip would claim this page
        // had been visited four times.
        boolean repeated = entry.visits > 1;
        h.count.setVisibility(repeated ? View.VISIBLE : View.GONE);
        if (repeated) {
            h.count.setText(context.getString(R.string.history_visit_count, entry.visits));
        }

        h.when.setText(whenOf(entry.visitedAt));

        h.itemView.setOnClickListener(v -> listener.onOpenVisit(entry));
        h.remove.setOnClickListener(v -> listener.onRemoveVisit(entry));
    }

    /**
     * A time for anything from today or yesterday, a date for everything older.
     *
     * <p>The heading has already said which day it was in the first two cases, so repeating it on
     * every row would be the same fact twice; beyond that the heading says only "earlier" and the
     * row is the only thing that can say when.
     */
    private String whenOf(long timestamp) {
        if (timestamp <= 0) return "";
        int bucket = bucketOf(timestamp);
        return bucket == EARLIER
                ? Formats.dayMonth(timestamp) : Formats.clock(context, timestamp);
    }

    /**
     * Bolds the part of a line the query matched — screen 12, panel B.
     *
     * <p>Case-insensitively, and only the first occurrence: the point is to show <em>why</em> a row
     * is in a filtered list, and marking every "pin" in a sentence about pins turns the line into
     * emphasis with a few plain words in it.
     */
    private CharSequence highlight(String text) {
        if (query.isEmpty() || TextUtils.isEmpty(text)) return text;

        int at = text.toLowerCase(Locale.getDefault())
                .indexOf(query.toLowerCase(Locale.getDefault()));
        if (at < 0) return text;

        SpannableString span = new SpannableString(text);
        span.setSpan(new StyleSpan(Typeface.BOLD), at, at + query.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return span;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class SectionHolder extends RecyclerView.ViewHolder {
        final TextView label;

        SectionHolder(@NonNull View v) {
            super(v);
            label = v.findViewById(R.id.sectionLabel);
        }
    }

    static class VisitHolder extends RecyclerView.ViewHolder {
        final ImageView icon;
        final TextView title;
        final TextView count;
        final TextView host;
        final TextView when;
        final ImageButton remove;

        VisitHolder(@NonNull View v) {
            super(v);
            icon = v.findViewById(R.id.visitIcon);
            title = v.findViewById(R.id.visitTitle);
            count = v.findViewById(R.id.visitCount);
            host = v.findViewById(R.id.visitHost);
            when = v.findViewById(R.id.visitWhen);
            remove = v.findViewById(R.id.visitRemove);
        }
    }
}
