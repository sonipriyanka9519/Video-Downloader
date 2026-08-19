package com.ms.webview.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.ms.webview.App;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.ui.settings.SettingsPrefs;
import com.ms.webview.MainActivity;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.detect.MediaItem;
import com.ms.webview.detect.MediaVariant;
import com.ms.webview.download.DownloadService;

import java.util.List;
import java.util.Locale;

/**
 * The pick sheet: one page per detected video, swiped horizontally.
 *
 * <p>It opens on whatever is playing on screen, and that page's thumbnail is a live frame from
 * the video rather than a static poster — on a reel feed the poster is often missing or wrong.
 */
public class MediaSheet extends BottomSheetDialogFragment {

    /**
     * How much of the screen the swipeable part of the sheet may take.
     *
     * <p>What is left over carries the sheet's header and the navigation inset. Deliberately short
     * of filling the screen: a sheet that reaches the top has stopped being a sheet.
     */
    private static final float PAGER_MAX_FRACTION = 0.70f;

    private MediaPagerAdapter adapter;

    private TextView sheetTitle;
    private TextView pageIndicator;
    private TextView emptyView;
    private ViewPager2 pager;
    private ImageButton btnPrev;
    private ImageButton btnNext;

    /**
     * Cleared the moment the user swipes, so we never yank the page out from under them — and
     * re-armed when a different video starts playing, because that swipe was about the video
     * they were looking at then, not about the one that has since come on screen.
     */
    private boolean followPlaying = true;

    /** Batch mode — screen 03, panel C. Hidden until the viewer asks for it. */
    private View selectMode;
    private TextView selectedCount;
    private ChipGroup batchQuality;
    private TextView btnSelectAll;
    private TextView batchTotalSize;
    private MaterialCheckBox checkSelectAll;
    private MaterialButton btnDownloadSelected;
    private SelectAdapter selectAdapter;
    private boolean selecting;
    /** The video on the page in view, remembered by identity rather than by page number. */
    @Nullable
    private String shownKey;
    /** The last video we followed, so a change of video can be told from a mere refresh. */
    @Nullable
    private String lastPlayingKey;
    private int unresolved;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_media, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        sheetTitle = view.findViewById(R.id.sheetTitle);
        pageIndicator = view.findViewById(R.id.pageIndicator);
        emptyView = view.findViewById(R.id.emptyView);
        pager = view.findViewById(R.id.pager);

        btnPrev = view.findViewById(R.id.btnPrev);
        btnNext = view.findViewById(R.id.btnNext);
        btnPrev.setOnClickListener(v -> step(-1));
        btnNext.setOnClickListener(v -> step(1));

        adapter = new MediaPagerAdapter(this::enqueue);
        adapter.setOnDownloadAll(this::enterSelectMode);
        pager.setAdapter(adapter);
        setUpPager();
        setUpSelectMode(view);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                shownKey = adapter.keyAt(position);
                updateIndicator(position);
                resizePagerToPage();
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) followPlaying = false;
            }
        });

        App.get().registry().live().observe(getViewLifecycleOwner(), this::render);
        App.get().registry().unresolved().observe(getViewLifecycleOwner(), count -> {
            unresolved = count == null ? 0 : count;
            updateEmptyText();
        });
    }

    /**
     * An empty sheet has two very different meanings, and saying so saves the user guessing:
     * nothing on the page, versus candidates found that could not be opened.
     */
    private void updateEmptyText() {
        if (adapter.getItemCount() > 0) return;
        emptyView.setText(unresolved > 0
                ? getResources().getQuantityString(R.plurals.videos_unreadable, unresolved, unresolved)
                : getString(R.string.no_videos_found));
    }

    /**
     * One video fills the page. Neighbours used to peek at the edges to advertise the swipe,
     * but sliced thumbnails read as a rendering fault; the arrows in the header make the
     * gesture discoverable without showing half a card.
     */
    private void setUpPager() {
        int gap = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._12sdp);
        pager.setOffscreenPageLimit(1);
        // Only visible mid-swipe, so pages do not touch as they slide past each other.
        pager.setPageTransformer(new MarginPageTransformer(gap));
    }

    /**
     * The tallest the pager may be: a fraction of the screen, leaving the sheet's own header, the
     * handle and the gesture inset room to sit outside it.
     *
     * <p>Measured off the screen rather than fixed, because the same three rows that fit on a tall
     * phone do not on a short one, and the answer has to be the room actually available.
     */
    private int maxPagerHeight() {
        int screen = getResources().getDisplayMetrics().heightPixels;
        return Math.round(screen * PAGER_MAX_FRACTION);
    }

    /**
     * Sets the pager's height to the page in front of the viewer.
     *
     * <p>ViewPager2 will not wrap its contents. Its pages are required to be {@code match_parent}
     * — it throws "Pages must fill the whole ViewPager2" otherwise — so a page cannot size the
     * pager by being short. Left at a fixed height instead, the pager has to be tall enough for
     * the tallest page, and every shorter one shows a band of empty sheet beneath it.
     *
     * <p>So the measurement is done here and applied outwards. The page is measured against an
     * unspecified height, which gives the height of what is actually in it, and the pager is set
     * to that. Called on every page change, and after each publish, because the number of quality
     * rows can change under a page that is already on screen.
     */
    private void resizePagerToPage() {
        if (pager == null || pager.getWidth() == 0) return;

        View page = currentPageView();
        if (page == null) {
            // The holder for this position is not attached yet — one frame after a swipe, or
            // immediately after a submit. Ask again once it is.
            pager.post(this::resizePagerToPage);
            return;
        }

        page.measure(
                View.MeasureSpec.makeMeasureSpec(pager.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = page.getMeasuredHeight();
        if (height <= 0) return;

        // Capped, and the page scrolls past the cap rather than growing through it. One row of
        // qualities makes a short page and a short sheet; three rows make a page taller than a
        // sheet is allowed to be, and letting it have that height pushed the Download button under
        // the gesture bar. See the NestedScrollView in page_media.
        height = Math.min(height, maxPagerHeight());

        ViewGroup.LayoutParams params = pager.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            pager.setLayoutParams(params);
        }
    }

    /** The view of the page currently in front, or null while it is being attached. */
    @Nullable
    private View currentPageView() {
        if (!(pager.getChildAt(0) instanceof RecyclerView)) return null;
        RecyclerView inner = (RecyclerView) pager.getChildAt(0);
        RecyclerView.ViewHolder holder =
                inner.findViewHolderForAdapterPosition(pager.getCurrentItem());
        return holder == null ? null : holder.itemView;
    }

    private void step(int delta) {
        int target = pager.getCurrentItem() + delta;
        if (target < 0 || target >= adapter.getItemCount()) return;
        followPlaying = false;
        pager.setCurrentItem(target, true);
    }

    private void updateNav(int position) {
        int count = adapter.getItemCount();
        boolean navigable = count > 1;
        btnPrev.setVisibility(navigable ? View.VISIBLE : View.GONE);
        btnNext.setVisibility(navigable ? View.VISIBLE : View.GONE);
        if (!navigable) return;

        setStepEnabled(btnPrev, position > 0);
        setStepEnabled(btnNext, position < count - 1);
    }

    private static void setStepEnabled(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.3f);
    }

    private void render(List<MediaItem> items) {
        int count = items == null ? 0 : items.size();
        adapter.submit(items);

        sheetTitle.setText(count == 0
                ? getString(R.string.videos_on_page)
                : getResources().getQuantityString(R.plurals.videos_found, count, count));

        boolean empty = count == 0;
        updateEmptyText();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        pageIndicator.setVisibility(empty || selecting ? View.GONE : View.VISIBLE);
        // The registry republishes constantly — a live frame every couple of seconds — so this
        // runs while batch mode is open and must not put the pager back over the top of it.
        pager.setVisibility(empty || selecting ? View.GONE : View.VISIBLE);

        if (selecting) {
            if (empty) {
                // Everything that was going to be downloaded has gone from the page.
                exitSelectMode();
            } else {
                // Keep the checklist in step with what is actually detected, without discarding
                // the ticks the viewer has already made.
                refreshBatchTotals();
            }
            return;
        }

        String playingKey = adapter.playingKey();
        if (playingKey != null && !playingKey.equals(lastPlayingKey)) {
            // A different video is on screen now. Any earlier swipe was a decision about the
            // previous one, so the sheet goes back to showing what is playing.
            lastPlayingKey = playingKey;
            followPlaying = true;
        }

        // Where to land after the list has re-sorted underneath us. Following the playing
        // video is the default; otherwise hold the exact card the user chose. Doing neither —
        // simply leaving the page number alone, as this used to — is what silently swapped a
        // different video onto the page in view and left the current one stranded elsewhere in
        // the list while the user sat at the far end of it.
        //
        // Which is also what happens when the sheet means to follow the playing video and there
        // is no longer one to follow: nothing is playing, so there is no index to move to, and
        // the page number is left pointing at whatever has since sorted into that slot. Holding
        // the card already in view is the right answer either way — it is still the video the
        // viewer was looking at, whether or not the page has stopped saying so.
        int target = followPlaying ? adapter.indexOf(playingKey) : -1;
        if (target < 0) target = adapter.indexOf(shownKey);
        if (target >= 0 && target != pager.getCurrentItem()) {
            pager.setCurrentItem(target, false);
        }

        shownKey = adapter.keyAt(pager.getCurrentItem());
        updateIndicator(pager.getCurrentItem());
        // Posted, not called straight away: this runs before the adapter has laid the rebound
        // page out, so measuring now would measure what was there a moment ago. A probe finishing
        // can add a rung and turn one row of qualities into two, so the height is re-asked on
        // every publish rather than only when the page changes.
        pager.post(this::resizePagerToPage);
    }

    private void updateIndicator(int position) {
        int count = adapter.getItemCount();
        pageIndicator.setText(count == 0
                ? "" : String.format(Locale.US, "%d / %d", position + 1, count));
        updateNav(position);
    }

    private void enqueue(MediaItem item, MediaVariant variant) {
        // Asked before the job is handed over, while the sheet still has a context to ask with.
        boolean waiting = SettingsPrefs.willWaitForWifi(requireContext());
        DownloadService.enqueue(requireContext(), item, variant);
        // Said by the activity, not here: this sheet closes on the same tap, and a message
        // raised from a view that is going away has nowhere to sit.
        //
        // Which message matters. "Processing" for a job that will not move until the phone finds
        // Wi-Fi is a failure dressed as progress; the viewer turned that setting on and is owed
        // the reminder that it is why nothing is happening.
        notice(getString(waiting ? R.string.queued_waiting_wifi : R.string.queued_toast));
        dismissAllowingStateLoss();
    }

    /** The word about a started download, above the tab bar — see MainActivity. */
    private void notice(String text) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).showDownloadNotice(text);
        }
    }

    // ------------------------------------------------------------------ batch

    /**
     * Batch mode — screen 03, panel C.
     *
     * <p>Hidden until asked for. The design is explicit that select mode never announces itself:
     * a page with one video should look exactly as simple as it did before this existed, and the
     * only way in is a text button that appears when there is genuinely more than one thing to
     * take.
     */
    private void setUpSelectMode(View view) {
        selectMode = view.findViewById(R.id.selectMode);
        selectedCount = view.findViewById(R.id.selectedCount);
        batchQuality = view.findViewById(R.id.batchQuality);
        btnSelectAll = view.findViewById(R.id.btnSelectAll);
        batchTotalSize = view.findViewById(R.id.batchTotalSize);
        checkSelectAll = view.findViewById(R.id.checkSelectAll);
        btnDownloadSelected = view.findViewById(R.id.btnDownloadSelected);
        RecyclerView list = view.findViewById(R.id.selectList);

        selectAdapter = new SelectAdapter(this::refreshBatchTotals);
        selectAdapter.setPicker(this::pickQualityFor);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(selectAdapter);

        // Screen 10's default quality decides where both halves of this sheet open — the chip row
        // here and each video's ladder in the pager. Read once, when the sheet is built, so a
        // preference change cannot move a tick out from under a finger mid-decision.
        BatchQuality preselect = BatchQuality.fromSetting(SettingsPrefs.quality(requireContext()));
        adapter.setPreselect(preselect);
        selectAdapter.setQuality(preselect);

        // One chip per intent, built once. Checked state drives the whole list.
        //
        // Inflated, not constructed. new Chip(context) takes the widget's own default style
        // rather than the chipStyle the theme names, so these came out as stock Material chips
        // with an outline and no accent fill — while every chip declared in a layout looked
        // right. Same fix as the library's filter row; see item_filter_chip.xml.
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (BatchQuality choice : BatchQuality.values()) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_filter_chip, batchQuality, false);
            chip.setText(choice.label);
            chip.setTag(choice);
            // Nothing checked under "Always ask" — preselect is null there, and no chip equals it.
            chip.setChecked(choice == preselect);
            batchQuality.addView(chip);
        }
        batchQuality.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            View chip = group.findViewById(ids.get(0));
            if (chip != null && chip.getTag() instanceof BatchQuality) {
                selectAdapter.setQuality((BatchQuality) chip.getTag());
            }
        });

        // A checkbox, so a second tap on a full set is the way back out. Its own click is off in
        // the layout — the row carries it, so the target is the full width.
        view.findViewById(R.id.rowSelectAll).setOnClickListener(v -> {
            if (selectAdapter.allTicked()) {
                selectAdapter.selectNone();
            } else {
                selectAdapter.selectAll();
            }
        });
        view.findViewById(R.id.btnCancelSelect).setOnClickListener(v -> exitSelectMode());
        btnDownloadSelected.setOnClickListener(v -> downloadBatch());
    }

    /**
     * One video's quality, chosen on its own — the row's pill.
     *
     * <p>The chip row above is a shortcut, not a rule. Four videos on a page are rarely wanted at
     * the same size, and under "Always ask" there is deliberately no shared answer at all, so each
     * row can be settled by itself and the button adds up whatever was actually chosen.
     *
     * <p>A dialog rather than a sheet: the sheet is already up, and this is one short list.
     */
    private void pickQualityFor(MediaItem item) {
        List<MediaVariant> rungs = selectAdapter.pickable(item);
        if (rungs.isEmpty()) return;

        CharSequence[] labels = new CharSequence[rungs.size()];
        int checked = -1;
        MediaVariant current = selectAdapter.rungFor(item);
        for (int i = 0; i < rungs.size(); i++) {
            MediaVariant v = rungs.get(i);
            String detail = v.qualityMeta(item.durationMs);
            labels[i] = detail.isEmpty() ? v.qualityName() : v.qualityName() + " · " + detail;
            if (v == current) checked = i;
        }

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(item.displayTitle())
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    selectAdapter.setQualityFor(item, rungs.get(which).url);
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void enterSelectMode() {
        selecting = true;
        selectAdapter.submit(adapter.current());
        applyMode();
    }

    private void exitSelectMode() {
        selecting = false;
        applyMode();
    }

    /** Batch mode replaces the pager; the header stays, because the count is still true. */
    private void applyMode() {
        pager.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectMode.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnPrev.setVisibility(selecting ? View.GONE : View.VISIBLE);
        btnNext.setVisibility(selecting ? View.GONE : View.VISIBLE);
        pageIndicator.setVisibility(selecting ? View.GONE : View.VISIBLE);
        selectedCount.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) refreshBatchTotals();
    }

    /** Keeps the count, the select-all label and the button's size in step with the ticks. */
    private void refreshBatchTotals() {
        if (selectAdapter == null || !selecting) return;

        int chosen = selectAdapter.selectedCount();
        int total = selectAdapter.total();
        selectedCount.setText(getString(R.string.selected_of, chosen, total));
        btnSelectAll.setText(getString(R.string.select_all_n, total));
        checkSelectAll.setChecked(selectAdapter.allTicked());

        // The page's own weight, not the selection's — the selection's is on the button. Left
        // blank while the sizes are still being probed rather than showing a confident zero.
        long pageBytes = selectAdapter.allBytes();
        batchTotalSize.setText(pageBytes > 0 ? Formats.bytes(pageBytes) : "");

        // What the button offers is what is settled, which under "Always ask" grows a row at a time
        // as each is chosen. Not the tick count: promising four and queueing two would be a lie
        // told at the last possible moment.
        int ready = selectAdapter.readyCount();
        long bytes = selectAdapter.totalBytes();
        btnDownloadSelected.setEnabled(ready > 0);
        if (ready == 0) {
            btnDownloadSelected.setText(R.string.pick_a_quality);
        } else {
            btnDownloadSelected.setText(bytes > 0
                    ? getString(R.string.download_n_size, ready, Formats.bytes(bytes))
                    : getString(R.string.download_n, ready));
        }
    }

    /**
     * Queues every ticked video at the shared quality.
     *
     * <p>Each video at its own rung — the shared chip resolved against it, or the one chosen for
     * that row alone. Asked of the adapter rather than resolved again here, so what is queued is
     * exactly what the list was showing; anything unsettled or unavailable is not in this batch at
     * all, and the count in the snackbar is what actually went to the queue.
     */
    private void downloadBatch() {
        List<MediaItem> batch = selectAdapter.resolvedItems();
        if (batch.isEmpty()) return;

        int queued = 0;
        for (MediaItem item : batch) {
            MediaVariant variant = selectAdapter.rungFor(item);
            if (variant == null) continue;
            DownloadService.enqueue(requireContext(), item, variant);
            queued++;
        }

        notice(SettingsPrefs.willWaitForWifi(requireContext())
                ? getResources().getQuantityString(R.plurals.queued_n_waiting_wifi, queued, queued)
                : getResources().getQuantityString(R.plurals.queued_n, queued, queued));
        dismissAllowingStateLoss();
    }
}
