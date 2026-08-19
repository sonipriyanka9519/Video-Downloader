package com.ms.webview.ui.home;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.ui.Thumbnails;

import java.util.ArrayList;
import java.util.List;

/**
 * The Recent Downloads row on Home — screen 01.
 *
 * <p>The only content row Home carries, and it exists only when there is something in it. A
 * transfer still running appears here too rather than in a queue of its own: same card, an accent
 * bar across the bottom of the thumbnail and "Downloading…" where the duration would be. That is
 * what lets Home show live state without a second badge story competing with the nav.
 */
public class RecentAdapter extends RecyclerView.Adapter<RecentAdapter.Holder> {

    /** Ten at most. Home is a front door, not the library. */
    public static final int MAX_ITEMS = 10;

    public interface Listener {
        void onOpen(DownloadEntity entity);
    }

    private final List<DownloadEntity> items = new ArrayList<>();
    private final Listener listener;

    public RecentAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<DownloadEntity> downloads) {
        items.clear();
        if (downloads != null) {
            for (DownloadEntity d : downloads) {
                if (items.size() >= MAX_ITEMS) break;
                items.add(d);
            }
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ds_recent, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        DownloadEntity d = items.get(position);

        h.title.setText(d.title);

        // A sound file has no still to show, so it says what it is instead of showing the film
        // placeholder — which is the one thing on this card that would be actively wrong.
        if (d.kind == MediaKind.AUDIO) {
            Thumbnails.audio(h.thumb);
        } else {
            // Cards are recycled, and the audio glyph leaves the view centred. Put the crop back
            // before loading, or the first video to reuse an audio card is letterboxed.
            h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            // Same headers the media was captured with: CDN posters 403 a bare image request.
            Thumbnails.load(h.thumb, d.posterUrl, d.headers());
        }

        boolean running = d.status == DownloadStatus.RUNNING
                || d.status == DownloadStatus.QUEUED
                || d.status == DownloadStatus.PUBLISHING;

        // Two states of one card. Both branches set both views, because a recycled card that
        // finished downloading would otherwise keep the previous row's bar.
        h.progress.setVisibility(running ? View.VISIBLE : View.GONE);
        h.subtitle.setVisibility(running ? View.VISIBLE : View.GONE);
        h.duration.setVisibility(running || d.durationMs <= 0 ? View.GONE : View.VISIBLE);

        if (running) {
            // A queued job has no byte total yet, so it says so by moving rather than by
            // claiming a percentage it does not have.
            boolean determinate = d.totalBytes > 0;
            h.progress.setIndeterminate(!determinate);
            if (determinate) h.progress.setProgress(d.percent());
        } else if (d.durationMs > 0) {
            h.duration.setText(Formats.duration(d.durationMs));
        }

        h.itemView.setOnClickListener(v -> listener.onOpen(d));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final TextView title;
        final TextView subtitle;
        final TextView duration;
        final LinearProgressIndicator progress;

        Holder(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.recentThumb);
            title = v.findViewById(R.id.recentTitle);
            subtitle = v.findViewById(R.id.recentSubtitle);
            duration = v.findViewById(R.id.recentDuration);
            progress = v.findViewById(R.id.recentProgress);
        }
    }
}
