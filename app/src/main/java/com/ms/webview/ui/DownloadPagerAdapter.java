package com.ms.webview.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * The two download pages — in progress and finished — as a swipeable pair.
 *
 * <p>Each page owns a list adapter for its whole lifetime rather than getting a new one on every
 * bind. A running download writes its progress several times a second, and rebuilding the
 * adapters on each of those would reset the scroll position and flicker the rows continuously.
 */
public class DownloadPagerAdapter extends RecyclerView.Adapter<DownloadPagerAdapter.PageHolder> {

    public static final int PAGE_ACTIVE = 0;
    public static final int PAGE_FINISHED = 1;

    private final DownloadAdapter activeList;
    private final DownloadAdapter finishedList;
    private final PageHolder[] holders = new PageHolder[2];

    private List<DownloadEntity> all = new ArrayList<>();

    public DownloadPagerAdapter(DownloadAdapter.Actions actions) {
        this.activeList = new DownloadAdapter(actions);
        this.finishedList = new DownloadAdapter(actions);
    }

    public void submit(List<DownloadEntity> downloads) {
        all = downloads == null ? new ArrayList<>() : downloads;
        activeList.submit(filter(false));
        finishedList.submit(filter(true));
        for (PageHolder holder : holders) {
            if (holder != null) applyEmptyState(holder);
        }
    }

    private List<DownloadEntity> filter(boolean finished) {
        List<DownloadEntity> shown = new ArrayList<>();
        for (DownloadEntity d : all) {
            if (isFinished(d) == finished) shown.add(d);
        }
        return shown;
    }

    /**
     * Cancelled downloads sit with the finished ones: they are over, and listing them under
     * "Downloading" would suggest they are still going somewhere.
     */
    private static boolean isFinished(DownloadEntity d) {
        return d.status == DownloadStatus.COMPLETED || d.status == DownloadStatus.CANCELLED;
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PageHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.page_downloads, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        holder.page = position;
        holders[position] = holder;

        holder.list.setLayoutManager(new LinearLayoutManager(holder.itemView.getContext()));
        holder.list.setAdapter(position == PAGE_ACTIVE ? activeList : finishedList);
        applyEmptyState(holder);
    }

    private void applyEmptyState(PageHolder holder) {
        boolean active = holder.page == PAGE_ACTIVE;
        boolean empty = (active ? activeList : finishedList).getItemCount() == 0;

        holder.empty.setText(active ? R.string.no_active_downloads : R.string.no_downloads);
        holder.empty.setVisibility(empty ? View.VISIBLE : View.GONE);
        holder.list.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public int getItemCount() {
        return 2;
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final RecyclerView list;
        final TextView empty;
        int page;

        PageHolder(@NonNull View v) {
            super(v);
            list = v.findViewById(R.id.pageList);
            empty = v.findViewById(R.id.pageEmpty);
        }
    }
}
