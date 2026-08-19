package com.ms.webview.ui.downloads;

import android.content.Context;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.download.DownloadSpeeds;
import com.ms.webview.ui.Thumbnails;
import com.ms.webview.ui.settings.SettingsPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

        /** Stop a transfer for good — a different decision from pausing it. */
        void cancel(DownloadEntity d);

        void open(DownloadEntity d);

        /** Everything else this row can do, which lives behind the ⋮. */
        void more(DownloadEntity d);

        /**
         * The selection changed, including entering and leaving select mode.
         *
         * <p>The adapter owns which rows are chosen — it is the only thing that knows what is on
         * screen — and the screen owns the header and the action bar that describe it.
         */
        void onSelectionChanged(int count, boolean selecting);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_LIST = 1;
    private static final int TYPE_GRID = 2;
    /**
     * A transfer still running.
     *
     * <p>Its own type in both layouts, and full width in the grid: a running download is being
     * watched rather than browsed, and half a tile is not enough room for a rate, a total and a
     * percent to be read at a glance.
     */
    private static final int TYPE_ACTIVE = 3;

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

    /**
     * Select mode — screen 07.
     *
     * <p>The chosen rows are held by id rather than by object, so a refresh from the repository
     * that replaces every entity does not silently empty the selection under the viewer.
     *
     * <p>Select mode is its own flag rather than "the set is not empty". Unticking the last row
     * leaves the mode on, which is what stops the header and the action bar from flickering away
     * mid-decision and reappearing on the next tap.
     */
    private final Set<Long> selected = new LinkedHashSet<>();
    private boolean selecting;

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

    /**
     * Still going, paused mid-way, or stopped by an error — either way it is not finished with.
     *
     * <p>A failure counts, and belongs in the pinned group with the rest. Filed under its day
     * beside the files that worked, a failed download reads as a saved one until you tap it.
     */
    private static boolean inFlight(DownloadEntity d) {
        return d.status.active()
                || d.status == DownloadStatus.PAUSED
                || d.status == DownloadStatus.FAILED;
    }

    public void setMode(Mode mode) {
        if (this.mode == mode) return;
        this.mode = mode;
        notifyDataSetChanged();
    }

    public Mode mode() {
        return mode;
    }

    // ------------------------------------------------------------------ select mode

    public boolean isSelecting() {
        return selecting;
    }

    /**
     * Which downloads are chosen, as entities rather than ids.
     *
     * <p>Read out of the current rows so the caller gets the live copy of each one — an id on
     * its own tells a share or a delete nothing about the file it names.
     */
    @NonNull
    public List<DownloadEntity> selection() {
        List<DownloadEntity> out = new ArrayList<>();
        for (Object row : rows) {
            if (row instanceof DownloadEntity && selected.contains(((DownloadEntity) row).id)) {
                out.add((DownloadEntity) row);
            }
        }
        return out;
    }

    /** Long-press: turns the list into a selection with that one row already chosen. */
    public void beginSelection(DownloadEntity d) {
        if (selecting || !selectable(d)) return;
        selecting = true;
        selected.clear();
        selected.add(d.id);
        notifyDataSetChanged();
        announce();
    }

    /**
     * Enters select mode with a given set of files already ticked — screen 13's handoff.
     *
     * <p>Matched on output uri rather than row id: the caller is another screen that knows which
     * <em>files</em> it means, and ids belong to this list's current contents.
     *
     * @return how many rows were actually ticked, which the caller needs in order to say so
     */
    public int beginSelection(@NonNull java.util.Collection<String> uris) {
        selecting = true;
        selected.clear();
        for (Object row : rows) {
            if (!(row instanceof DownloadEntity)) continue;
            DownloadEntity d = (DownloadEntity) row;
            if (selectable(d) && d.outputUri != null && uris.contains(d.outputUri)) {
                selected.add(d.id);
            }
        }
        notifyDataSetChanged();
        announce();
        return selected.size();
    }

    public void toggleSelection(DownloadEntity d) {
        if (!selecting || !selectable(d)) return;
        if (!selected.remove(d.id)) selected.add(d.id);
        notifyDataSetChanged();
        announce();
    }

    /**
     * Everything the list is currently showing, which is not the same as everything downloaded —
     * a filter or a search is still in force, and "Select all" means all of what is in front of
     * you rather than all of what exists.
     */
    public void selectAll() {
        if (!selecting) return;
        for (Object row : rows) {
            if (row instanceof DownloadEntity && selectable((DownloadEntity) row)) {
                selected.add(((DownloadEntity) row).id);
            }
        }
        notifyDataSetChanged();
        announce();
    }

    public void clearSelection() {
        if (!selecting) return;
        selecting = false;
        selected.clear();
        notifyDataSetChanged();
        announce();
    }

    /**
     * Only a finished file can be chosen.
     *
     * <p>Every action on the bar acts on a file: sharing one, filing one, deleting one. A
     * transfer still running has none of those, and letting it be ticked would mean a count that
     * three of the four actions could not honour.
     */
    private static boolean selectable(DownloadEntity d) {
        return d != null && d.status == DownloadStatus.COMPLETED;
    }

    private void announce() {
        actions.onSelectionChanged(selected.size(), selecting);
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
        if (inFlight((DownloadEntity) rows.get(position))) return TYPE_ACTIVE;
        return mode == Mode.GRID ? TYPE_GRID : TYPE_LIST;
    }

    /** Asked by the grid's span lookup along with {@link #isHeader}. */
    public boolean isFullWidth(int position) {
        return getItemViewType(position) != TYPE_GRID;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderHolder(
                    inflater.inflate(R.layout.item_download_header, parent, false));
        }
        if (viewType == TYPE_ACTIVE) {
            return new ActiveHolder(
                    inflater.inflate(R.layout.item_download_active, parent, false));
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
        if (holder instanceof ActiveHolder) {
            bindActive((ActiveHolder) holder, (DownloadEntity) row);
            return;
        }
        bind((DownloadHolder) holder, (DownloadEntity) row);
    }

    /**
     * A transfer in flight — screen 06's DOWNLOADING zone.
     *
     * <p>Every branch here sets every property it can change. A failed row that only added its
     * error icon would keep the last row's rate line and its pause button after recycling, and
     * the result would be a failure claiming to be running at 2 MB/s.
     */
    private void bindActive(ActiveHolder h, DownloadEntity d) {
        Context ctx = h.itemView.getContext();
        boolean failed = d.status == DownloadStatus.FAILED;

        h.title.setText(d.title);
        h.status.setText(statusLine(ctx, d));
        h.errorIcon.setVisibility(failed ? View.VISIBLE : View.GONE);

        // Same headers the media was captured with: CDN posters 403 a bare image request.
        h.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Thumbnails.load(h.thumb, d.posterUrl, d.headers());

        // An HLS run knows its segment count before it knows any byte total.
        boolean determinate = d.totalBytes > 0
                || (d.kind == MediaKind.HLS && d.segmentTotal > 0);
        int percent = determinate ? d.percent() : 0;

        h.progress.setIndicatorColor(ContextCompat.getColor(ctx,
                failed ? R.color.ds_error : R.color.ds_accent));
        if (determinate) {
            h.progress.setIndeterminate(false);
            h.progress.setProgress(percent);
        } else {
            // Indeterminate only while it is actually moving. A paused transfer with no total
            // would otherwise animate forever, which reads as progress that is not happening.
            h.progress.setIndeterminate(d.status == DownloadStatus.RUNNING);
        }

        // The number over the picture is only shown when it means something. A sweeping bar and
        // a frozen "0%" side by side say two different things about the same transfer.
        h.percent.setVisibility(determinate ? View.VISIBLE : View.GONE);
        h.thumbScrim.setVisibility(determinate ? View.VISIBLE : View.GONE);
        if (determinate) h.percent.setText(ctx.getString(R.string.percent, percent));

        // Asked to stop but not stopped yet - see PendingPause. Read once and applied on every
        // branch, because a property set in one branch and not the others is what a recycled row
        // shows from the row before it.
        boolean pausing = PendingPause.isPausing(d);
        h.primary.setEnabled(!pausing);
        h.primary.setAlpha(pausing ? 0.5f : 1f);

        switch (d.status) {
            case PAUSED:
                h.primary.setImageResource(R.drawable.ic_play);
                h.primary.setContentDescription(ctx.getString(R.string.resume));
                h.primary.setOnClickListener(v -> actions.resume(d));
                break;
            case FAILED:
                h.primary.setImageResource(R.drawable.ic_refresh);
                h.primary.setContentDescription(ctx.getString(R.string.retry));
                h.primary.setOnClickListener(v -> actions.resume(d));
                break;
            default:
                h.primary.setImageResource(R.drawable.ic_pause);
                h.primary.setContentDescription(ctx.getString(R.string.pause));
                // Spent once asked: the second tap would do nothing, and a control that looks
                // live but is not invites exactly that tap.
                h.primary.setOnClickListener(v -> {
                    PendingPause.requested(d.id);
                    actions.pause(d);
                    notifyItemChanged(rows.indexOf(d));
                });
                break;
        }

        h.cancel.setOnClickListener(v -> actions.cancel(d));

        // Nothing to open yet, and the ⋮ has nothing to offer a file that is not there — but a
        // long press still reaches delete, which is the one thing you can do to a bad transfer.
        h.itemView.setOnClickListener(null);
        h.itemView.setOnLongClickListener(v -> {
            actions.more(d);
            return true;
        });
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

        // The badge is dropped for sound: it is drawn over a preview to say the still is really a
        // video, and there is no still here for it to qualify.
        h.playBadge.setVisibility(done && !audio ? View.VISIBLE : View.GONE);

        // Over the picture. The metadata line carries it a second time — see statusLine — which
        // is what screen 06 shows: on the thumbnail while scanning, in the line while reading.
        boolean hasDuration = done && d.durationMs > 0;
        h.duration.setVisibility(hasDuration ? View.VISIBLE : View.GONE);
        if (hasDuration) h.duration.setText(Formats.duration(d.durationMs));

        // How much of it has been watched, and whether it has been watched at all. Both read the
        // one stored fraction, so the dot and the bar can never contradict each other.
        //
        // Only on a finished file: an unwatched dot on something still arriving would be true and
        // useless, and the download bar already owns the bottom of the thumbnail while it runs.
        float watched = done ? WatchedStore.progress(ctx, d.outputUri) : 0f;
        // Shown for anything started, including something watched to the end — a full bar is how
        // a row says "you have seen all of this", and hiding it at 100% made a finished video
        // look identical to one never opened.
        boolean anyWatched = done && watched > 0f;
        // Three steps, and each one is answering a different way this bar used to lie.
        //
        // The value is written on every path, including the hidden one, because hiding a bar does
        // not reset it: a recycled holder that last showed a fully-watched video still holds 100,
        // and that is what flashed before the real number arrived.
        //
        // The visibility comes after the value, so there is never a frame showing the old one.
        //
        // And the drawables are jumped to their end state afterwards, which is the part neither of
        // the first two fixed. LinearProgressIndicator springs to a new level whenever it becomes
        // visible - see BaseProgressIndicator.onVisibilityChanged - so ordering alone only changed
        // what it animated from: first the previous row's value, then zero. This is the documented
        // way to say "no transition, draw the end state now", and it is what makes a row that
        // scrolls into view look identical to one that was already there.
        h.watchedBar.setIndeterminate(false);
        h.watchedBar.setProgressCompat(anyWatched ? Math.round(watched * 100f) : 0, false);
        h.watchedBar.setVisibility(anyWatched ? View.VISIBLE : View.GONE);
        h.watchedBar.jumpDrawablesToCurrentState();
        h.unwatchedDot.setVisibility(done && watched <= 0f ? View.VISIBLE : View.GONE);

        // Select mode — screen 07. Two signals, never one: the row takes an accent-soft wash and
        // its tick fills. A tick alone is a 22dp target's worth of evidence for a decision that
        // might be about to delete something.
        boolean chosen = selected.contains(d.id);
        h.selectTick.setVisibility(selecting && done ? View.VISIBLE : View.GONE);
        h.selectTick.setSelected(chosen);
        h.selectCheck.setVisibility(chosen ? View.VISIBLE : View.GONE);
        // Set on the card rather than as a plain background: a colour laid over a MaterialCardView
        // paints across its rounded corners and squares the row off. The resting colour is the
        // one the card was inflated with, so the theme still decides what a row looks like.
        h.card.setCardBackgroundColor(chosen
                ? ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.ds_accent_soft))
                : h.restingCardColor);

        // The ⋮ has nothing to say about a row that is one of five: its actions are all about a
        // single file, and the bar at the foot of the screen is where a group's actions live.
        h.more.setVisibility(selecting ? View.GONE : View.VISIBLE);
        h.more.setOnClickListener(v -> actions.more(d));

        h.itemView.setOnClickListener(v -> {
            if (selecting) {
                toggleSelection(d);
            } else if (done) {
                actions.open(d);
            }
        });
        // Long-press is how a selection starts, and it starts on the row that was pressed.
        h.itemView.setOnLongClickListener(v -> {
            if (!done) return false;
            beginSelection(d);
            return true;
        });
    }

    private String statusLine(Context ctx, DownloadEntity d) {
        switch (d.status) {
            case QUEUED:
                // Says which kind of waiting this is. "Queued" on a download that will not move
                // until the phone finds Wi-Fi reads as a download that is stuck.
                return SettingsPrefs.wifiOnly(ctx)
                        ? ctx.getString(R.string.status_waiting_wifi)
                        : ctx.getString(R.string.status_queued);
            case RUNNING:
                // Asked to stop but not stopped yet. Said plainly rather than left looking
                // untouched, because the bar can still creep for a moment afterwards and a row
                // that ignores a tap invites a second one.
                if (PendingPause.isPausing(d)) return ctx.getString(R.string.status_pausing);
                // Amount first, rate second — "12.4 MB of 24.1 MB • 2.1 MB/s", per screen 06.
                // How far along it is is the question; how fast is the follow-up, and it is also
                // the part that keeps changing, so it goes where it cannot shift the rest.
                String amount = d.kind == MediaKind.HLS && d.segmentTotal > 0
                        ? ctx.getString(R.string.segments_progress, d.segmentsDone, d.segmentTotal)
                        : d.totalBytes > 0
                        ? ctx.getString(R.string.bytes_of_bytes,
                        Formats.bytes(d.downloadedBytes), Formats.bytes(d.totalBytes))
                        : Formats.bytes(d.downloadedBytes);
                String rate = Formats.speed(DownloadSpeeds.of(d.id));
                return rate.isEmpty() ? amount : amount + " • " + rate;
            case PAUSED:
                return ctx.getString(R.string.status_paused) + " · "
                        + Formats.bytes(d.downloadedBytes);
            case PUBLISHING:
                // For HLS this phase is the remux, which is the slow part worth naming.
                return ctx.getString(d.kind == MediaKind.HLS
                        ? R.string.status_muxing : R.string.status_publishing);
            case COMPLETED:
                // "4.2 MB • 480p • 0:43" — size, then quality, then how long it runs, each part
                // dropped rather than shown empty when the file does not have it.
                return Formats.bytes(d.totalBytes)
                        + (TextUtils.isEmpty(d.quality) ? "" : " • " + d.quality)
                        + (d.durationMs > 0 ? " • " + Formats.duration(d.durationMs) : "");
            case FAILED:
                // The remedy alone, where there is one: the row already carries an error icon,
                // and "Download failed · Link expired — reopen the page" spends its first two
                // words on something the icon has said and the reader can see.
                return TextUtils.isEmpty(d.error)
                        ? ctx.getString(R.string.download_failed) : d.error;
            case CANCELLED:
            default:
                return ctx.getString(R.string.status_cancelled);
        }
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    /**
     * A transfer in flight.
     *
     * <p>Deliberately not the same holder as a finished row: the two layouts share almost
     * nothing, and pretending they did would mean null checks on half the fields.
     */
    static class ActiveHolder extends RecyclerView.ViewHolder {

        final ImageView thumb;
        final View thumbScrim;
        final TextView percent;
        final ImageView errorIcon;
        final TextView title;
        final TextView status;
        final LinearProgressIndicator progress;
        final ImageButton primary;
        final ImageButton cancel;

        ActiveHolder(@NonNull View v) {
            super(v);
            thumb = v.findViewById(R.id.thumb);
            thumbScrim = v.findViewById(R.id.thumbScrim);
            percent = v.findViewById(R.id.percent);
            errorIcon = v.findViewById(R.id.errorIcon);
            title = v.findViewById(R.id.title);
            status = v.findViewById(R.id.status);
            progress = v.findViewById(R.id.progress);
            primary = v.findViewById(R.id.btnPrimary);
            cancel = v.findViewById(R.id.btnCancel);
        }
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
        final LinearProgressIndicator watchedBar;
        final View unwatchedDot;
        final ImageButton more;
        final MaterialCardView card;
        final View selectTick;
        final View selectCheck;
        /** What the card looks like when it is not chosen, captured before anything changes it. */
        final ColorStateList restingCardColor;

        DownloadHolder(@NonNull View v) {
            super(v);
            card = (MaterialCardView) v;
            restingCardColor = card.getCardBackgroundColor();
            selectTick = v.findViewById(R.id.selectTick);
            selectCheck = v.findViewById(R.id.selectCheck);
            thumb = v.findViewById(R.id.thumb);
            playBadge = v.findViewById(R.id.playBadge);
            duration = v.findViewById(R.id.duration);
            title = v.findViewById(R.id.title);
            status = v.findViewById(R.id.status);
            watchedBar = v.findViewById(R.id.watchedBar);
            unwatchedDot = v.findViewById(R.id.unwatchedDot);
            more = v.findViewById(R.id.btnMore);
        }
    }
}
