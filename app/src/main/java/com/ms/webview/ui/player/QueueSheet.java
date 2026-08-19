package com.ms.webview.ui.player;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.ui.Thumbnails;

/**
 * What plays after this one — screen 09, panel C.
 *
 * <p>Dark, and deliberately not the app's sheet: it is raised over video on a screen that is
 * black whatever theme the app is in, and a light panel sliding up over a film would be the one
 * thing here that broke the immersion.
 *
 * <p>The video playing now keeps its place in the list rather than being lifted out of it.
 * Seeing where you are among what is coming is the whole reason to open this.
 */
public final class QueueSheet {

    public interface Listener {
        /** Play the row that was tapped. */
        void onQueueItemChosen(int position);

        void onAutoplayChanged(boolean enabled);
    }

    private final Context context;
    private final PlayerQueue queue;
    private final boolean autoplay;
    private final Listener listener;

    private BottomSheetDialog dialog;

    public QueueSheet(@NonNull Context context, @NonNull PlayerQueue queue, boolean autoplay,
                      @NonNull Listener listener) {
        this.context = context;
        this.queue = queue;
        this.autoplay = autoplay;
        this.listener = listener;
    }

    public void show() {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.sheet_player_queue, null, false);

        TextView scope = content.findViewById(R.id.queueScope);
        scope.setText(TextUtils.isEmpty(queue.scope())
                ? context.getString(R.string.queue_scope_all)
                : context.getString(R.string.queue_scope_named, queue.scope()));

        MaterialSwitch toggle = content.findViewById(R.id.queueAutoplay);
        toggle.setChecked(autoplay);
        toggle.setOnCheckedChangeListener((v, checked) -> listener.onAutoplayChanged(checked));

        RecyclerView list = content.findViewById(R.id.queueList);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new Adapter());
        // Opens on whatever is playing rather than at the top: with a long queue the row that
        // says "Playing now" is the one the sheet was opened to find.
        list.scrollToPosition(queue.index());

        // The DS overlay, like every other sheet. The layout inside keeps its ds_player_ surfaces
        // — the player is deliberately its own dark world — but the container the sheet draws
        // behind those rounded corners should not be Material's default.
        dialog = new BottomSheetDialog(context, R.style.ThemeOverlay_Ds_BottomSheet);
        dialog.setContentView(content);

        // A peek rather than the full screen. Left to itself a queue the length of the library
        // arrives as a second screen with a video behind it; dragging up is still there for
        // anyone who wants the whole list.
        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from((View) content.getParent());
        behavior.setPeekHeight(context.getResources()
                .getDimensionPixelSize(R.dimen.ds_player_queue_peek));

        dialog.show();
    }

    public void dismiss() {
        if (dialog != null) dialog.dismiss();
        dialog = null;
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_player_queue, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            PlayerQueue.Item item = queue.at(position);
            if (item == null) return;

            boolean current = position == queue.index();
            holder.title.setText(item.title);
            // Both branches set both, because a recycled row that only added its rail would
            // carry the last row's "Playing now" under a different video's name.
            holder.rail.setVisibility(current ? View.VISIBLE : View.INVISIBLE);
            holder.nowIcon.setVisibility(current ? View.VISIBLE : View.GONE);
            holder.meta.setText(current
                    ? context.getString(R.string.playing_now)
                    : Formats.duration(item.durationMs));
            holder.meta.setTextColor(context.getColor(current
                    ? R.color.ds_player_accent : R.color.ds_player_ink_dim));
            holder.row.setBackgroundColor(context.getColor(current
                    ? R.color.ds_player_row : android.R.color.transparent));

            if (TextUtils.isEmpty(item.posterUrl)) {
                holder.thumb.setImageDrawable(null);
            } else {
                holder.thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                Thumbnails.load(holder.thumb, item.posterUrl, null);
            }

            holder.itemView.setOnClickListener(v -> {
                dismiss();
                listener.onQueueItemChosen(position);
            });
        }

        @Override
        public int getItemCount() {
            return queue.size();
        }
    }

    static final class Holder extends RecyclerView.ViewHolder {

        final View row;
        final View rail;
        final ImageView thumb;
        final TextView title;
        final TextView meta;
        final ImageView nowIcon;

        Holder(@NonNull View v) {
            super(v);
            row = v.findViewById(R.id.queueRow);
            rail = v.findViewById(R.id.queueRail);
            thumb = v.findViewById(R.id.queueThumb);
            title = v.findViewById(R.id.queueTitle);
            meta = v.findViewById(R.id.queueMeta);
            nowIcon = v.findViewById(R.id.queueNowIcon);
        }
    }
}
