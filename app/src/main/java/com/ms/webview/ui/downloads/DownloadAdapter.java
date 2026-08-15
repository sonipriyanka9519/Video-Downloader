package com.ms.webview.ui.downloads;

import android.content.Context;
import android.text.TextUtils;
import android.text.format.DateUtils;
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
import com.ms.webview.detect.MediaKind;
import com.ms.webview.download.DownloadSpeeds;
import com.ms.webview.ui.Thumbnails;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every download in one list: the ones still arriving, then the ones already here under the day
 * they belong to.
 *
 * <p>They used to be two lists behind two tabs, and the split cost more than it explained. A
 * download that finished while you were looking at it vanished from the page you were on and
 * appeared on the one you were not; and the question people actually ask of this screen — did the
 * thing I just saved arrive — needed both tabs to answer.
 */
public class DownloadAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** How much room one download gets: a row across the screen, or a tile among others. */
    public enum Mode {
        LIST, GRID
    }

    public interface Actions {
        void pause(DownloadEntity d);

        void resume(DownloadEntity d);

        void open(DownloadEntity d);

        /** Everything else this row can do, which lives behind the ⋮. */
        void more(DownloadEntity d);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_LIST = 1;
    private static final int TYPE_GRID = 2;

    /**
     * Headers and downloads in the order they are drawn.
     *
     * <p>A flat list of both rather than a list of groups, because that is the shape a
     * RecyclerView reads. Every entry is either a {@link String} — a heading — or a
     * {@link DownloadEntity}.
     */
    private final List<Object> rows = new ArrayList<>();
    private final Actions actions;
    private Mode mode = Mode.LIST;
    private DownloadSort sort = DownloadSort.NEWEST;

    public DownloadAdapter(Actions actions) {
        this.actions = actions;
    }

    /**
     * Replaces the list: anything still arriving first, then everything else in the chosen order.
     *
     * <p>Transfers in flight are pinned to the top and left out of the sort entirely. They are the
     * one part of this screen that is changing while it is being looked at, and sorting them by
     * name or size would file them somewhere down the list where nobody watching a download would
     * think to look.
     */
    public void submit(Context context, List<DownloadEntity> downloads, DownloadSort sort) {
        this.sort = sort;
        rows.clear();

        List<DownloadEntity> running = new ArrayList<>();
        List<DownloadEntity> rest = new ArrayList<>();
        if (downloads != null) {
            for (DownloadEntity d : downloads) {
                if (inFlight(d)) running.add(d);
                else rest.add(d);
            }
        }

        if (!running.isEmpty()) {
            rows.add(context.getString(R.string.downloads_in_progress));
            rows.addAll(running);
        }

        Collections.sort(rest, sort.comparator());

        String openGroup = null;
        for (DownloadEntity d : rest) {
            if (sort.byDate()) {
                String group = dayOf(context, DownloadSort.whenOf(d));
                if (!group.equals(openGroup)) {
                    rows.add(group);
                    openGroup = group;
                }
            }
            rows.add(d);
        }
        notifyDataSetChanged();
    }

    /** Still going, or paused mid-way — either way it is not finished with. */
    private static boolean inFlight(DownloadEntity d) {
        return d.status.active() || d.status == DownloadStatus.PAUSED;
    }

    public void setMode(Mode mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        notifyDataSetChanged();
    }

    public Mode mode() {
        return mode;
    }

    /** Asked by the grid's span lookup: a heading takes the full width, a download does not. */
    public boolean isHeader(int position) {
        return position >= 0 && position < rows.size() && rows.get(position) instanceof String;
    }

    /**
     * The heading for a day: "Today" and "Yesterday" carry their date as well.
     *
     * <p>Both, rather than one or the other. The word is what people scan for; the date is what
     * they need the moment they are looking for something from a particular day, and a heading
     * that only says "Today" makes the list below it undateable.
     */
    private static String dayOf(Context context, long time) {
        if (time <= 0) return context.getString(R.string.downloads_undated);

        String date = DateUtils.formatDateTime(context, time,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                        | DateUtils.FORMAT_NO_YEAR);

        if (DateUtils.isToday(time)) {
            return context.getString(R.string.downloads_today, date);
        }
        if (DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS)) {
            return context.getString(R.string.downloads_yesterday, date);
        }
        // Older than that, the year stops being obvious and starts being needed.
        return DateUtils.formatDateTime(context, time,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_ABBREV_MONTH
                        | DateUtils.FORMAT_SHOW_YEAR);
    }

    @Override
    public int getItemViewType(int position) {
        if (isHeader(position)) return TYPE_HEADER;
        return mode == Mode.GRID ? TYPE_GRID : TYPE_LIST;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(
                    inflater.inflate(R.layout.item_download_header, parent, false));
        }
        return new DownloadHolder(inflater.inflate(viewType == TYPE_GRID
                ? R.layout.item_download_grid : R.layout.item_download, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object row = rows.get(position);
        if (holder instanceof HeaderHolder) {
            ((HeaderHolder) holder).day.setText((String) row);
            return;
        }
        bind((DownloadHolder) holder, (DownloadEntity) row);
    }

    private void bind(DownloadHolder h, DownloadEntity d) {
        Context ctx = h.itemView.getContext();
        boolean done = d.status == DownloadStatus.COMPLETED;

        h.title.setText(d.title);
        h.status.setText(statusLine(ctx, d));

        boolean audio = d.kind == MediaKind.AUDIO;
        if (audio) {
            Thumbnails.audio(h.thumb);
        } else {
            // Rows are recycled, and the audio glyph leaves the view centred. Put the crop back
            // before loading, or the first video to reuse an audio row's view is letterboxed.
            h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            // Same headers the media was captured with: CDN posters 403 a bare image request.
            Thumbnails.load(h.thumb, d.posterUrl, d.headers());
        }

        // A play badge, a running time and a play glyph beside the size all say the same thing —
        // that this is a file you can watch — so all three appear together or not at all. The
        // badge is dropped for sound: it is drawn over a preview to say the still is really a
        // video, and there is no still here for it to qualify.
        h.playBadge.setVisibility(done && !audio ? View.VISIBLE : View.GONE);
        h.statusIcon.setVisibility(done ? View.VISIBLE : View.GONE);

        boolean hasDuration = done && d.durationMs > 0;
        h.duration.setVisibility(hasDuration ? View.VISIBLE : View.GONE);
        if (hasDuration) h.duration.setText(Formats.duration(d.durationMs));

        boolean showProgress = inFlight(d);
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

        // Pause and resume, and nothing else. A finished row needs no button of its own: tapping
        // it plays, and everything rarer than that is behind the ⋮.
        switch (d.status) {
            case RUNNING:
            case QUEUED:
            case PUBLISHING:
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_pause);
                h.primary.setContentDescription(ctx.getString(R.string.pause));
                h.primary.setOnClickListener(v -> actions.pause(d));
                break;
            case PAUSED:
            case FAILED:
                h.primary.setVisibility(View.VISIBLE);
                h.primary.setImageResource(R.drawable.ic_play);
                h.primary.setContentDescription(ctx.getString(R.string.resume));
                h.primary.setOnClickListener(v -> actions.resume(d));
                break;
            default:
                h.primary.setVisibility(View.GONE);
                h.primary.setOnClickListener(null);
                break;
        }

        h.more.setOnClickListener(v -> actions.more(d));
        h.itemView.setOnClickListener(v -> {
            if (done) actions.open(d);
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
        return rows.size();
    }

    static class HeaderHolder extends RecyclerView.ViewHolder {

        final TextView day;

        HeaderHolder(@NonNull View v) {
            super(v);
            day = v.findViewById(R.id.downloadDay);
        }
    }

    /**
     * One download, in either layout.
     *
     * <p>The two layouts carry the same ids on purpose, so there is one holder and one binding for
     * both — what differs between a row and a tile is where the parts sit, not what they are.
     */
    static class DownloadHolder extends RecyclerView.ViewHolder {

        final ImageView thumb;
        final View playBadge;
        final TextView duration;
        final TextView title;
        final TextView status;
        final ImageView statusIcon;
        final LinearProgressIndicator progress;
        final ImageButton primary;
        final ImageButton more;

        DownloadHolder(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            playBadge = v.findViewById(R.id.playBadge);
            duration = v.findViewById(R.id.duration);
            title = v.findViewById(R.id.title);
            status = v.findViewById(R.id.status);
            statusIcon = v.findViewById(R.id.statusIcon);
            progress = v.findViewById(R.id.progress);
            primary = v.findViewById(R.id.btnPrimary);
            more = v.findViewById(R.id.btnMore);
        }
    }
}
