package com.ms.webview.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.download.DownloadSpeeds;
import com.ms.webview.detect.MediaKind;

import java.util.ArrayList;
import java.util.List;

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.VH> {

    public interface Actions {
        void pause(DownloadEntity d);

        void resume(DownloadEntity d);

        void cancel(DownloadEntity d);

        void delete(DownloadEntity d);

        void open(DownloadEntity d);
    }

    private final List<DownloadEntity> items = new ArrayList<>();
    private final Actions actions;

    public DownloadAdapter(Actions actions) {
        this.actions = actions;
    }

    public void submit(List<DownloadEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_download, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        DownloadEntity d = items.get(position);
        Context ctx = h.itemView.getContext();

        h.title.setText(d.title);
        h.status.setText(statusLine(ctx, d));

        // Same headers the media was captured with: CDN posters 403 a bare image request.
        Thumbnails.load(h.thumb, d.posterUrl, d.headers());

        boolean showProgress = d.status.active() || d.status == DownloadStatus.PAUSED;
        h.progress.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        if (showProgress) {
            // An HLS run knows its segment count before it knows any byte total.
            boolean determinate = d.totalBytes > 0
                    || (d.kind == MediaKind.HLS && d.segmentTotal > 0);
            if (determinate) {
                h.progress.setIndeterminate(false);
                h.progress.setProgress(d.percent());
            } else {
                h.progress.setIndeterminate(d.status == DownloadStatus.RUNNING);
            }
        }

        // A play badge only where tapping actually plays something.
        h.playBadge.setVisibility(d.status == DownloadStatus.COMPLETED ? View.VISIBLE : View.GONE);

        switch (d.status) {
            case RUNNING:
            case QUEUED:
            case PUBLISHING:
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_pause);
                h.primary.setContentDescription(ctx.getString(R.string.pause));
                h.primary.setOnClickListener(v -> actions.pause(d));
                h.delete.setOnClickListener(v -> actions.cancel(d));
                break;
            case PAUSED:
            case FAILED:
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_play);
                h.primary.setContentDescription(ctx.getString(R.string.resume));
                h.primary.setOnClickListener(v -> actions.resume(d));
                h.delete.setOnClickListener(v -> actions.delete(d));
                break;
            case COMPLETED:
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_play);
                h.primary.setContentDescription(ctx.getString(R.string.open));
                h.primary.setOnClickListener(v -> actions.open(d));
                h.delete.setOnClickListener(v -> actions.delete(d));
                break;
            default:
                h.primary.setVisibility(View.GONE);
                h.delete.setOnClickListener(v -> actions.delete(d));
                break;
        }

        h.itemView.setOnClickListener(v -> {
            if (d.status == DownloadStatus.COMPLETED) actions.open(d);
        });
    }

    private String statusLine(Context ctx, DownloadEntity d) {
        switch (d.status) {
            case QUEUED:
                return ctx.getString(R.string.status_queued);
            case RUNNING:
                // Rate first, since it is the part that changes and the part people watch.
                String rate = Formats.speed(DownloadSpeeds.of(d.id));
                String amount = d.kind == MediaKind.HLS && d.segmentTotal > 0
                        ? ctx.getString(R.string.segments_progress, d.segmentsDone, d.segmentTotal)
                        : Formats.bytes(d.downloadedBytes)
                        + (d.totalBytes > 0 ? " / " + Formats.bytes(d.totalBytes) : "");
                return rate.isEmpty() ? amount : rate + " • " + amount;
            case PAUSED:
                return ctx.getString(R.string.status_paused) + " · "
                        + Formats.bytes(d.downloadedBytes);
            case PUBLISHING:
                // For HLS this phase is the remux, which is the slow part worth naming.
                return ctx.getString(d.kind == MediaKind.HLS
                        ? R.string.status_muxing : R.string.status_publishing);
            case COMPLETED:
                return Formats.bytes(d.totalBytes)
                        + (TextUtils.isEmpty(d.quality) ? "" : " • " + d.quality);
            case FAILED:
                return ctx.getString(R.string.download_failed)
                        + (TextUtils.isEmpty(d.error) ? "" : " · " + d.error);
            case CANCELLED:
            default:
                return ctx.getString(R.string.status_cancelled);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final ImageView thumb;
        final View playBadge;
        final TextView title;
        final TextView status;
        final LinearProgressIndicator progress;
        final ImageButton primary;
        final ImageButton delete;

        VH(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            playBadge = v.findViewById(R.id.playBadge);
            title = v.findViewById(R.id.title);
            status = v.findViewById(R.id.status);
            progress = v.findViewById(R.id.progress);
            primary = v.findViewById(R.id.btnPrimary);
            delete = v.findViewById(R.id.btnDelete);
        }
    }
}
