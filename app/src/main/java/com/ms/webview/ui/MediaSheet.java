package com.ms.webview.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewpager2.widget.MarginPageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.ms.webview.App;
import com.ms.webview.R;
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
        pager.setAdapter(adapter);
        setUpPager();

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                shownKey = adapter.keyAt(position);
                updateIndicator(position);
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
        pager.setVisibility(empty ? View.GONE : View.VISIBLE);
        pageIndicator.setVisibility(empty ? View.GONE : View.VISIBLE);

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
    }

    private void updateIndicator(int position) {
        int count = adapter.getItemCount();
        pageIndicator.setText(count == 0
                ? "" : String.format(Locale.US, "%d / %d", position + 1, count));
        updateNav(position);
    }

    private void enqueue(MediaItem item, MediaVariant variant) {
        DownloadService.enqueue(requireContext(), item, variant);
        Toast.makeText(requireContext(), R.string.queued_toast, Toast.LENGTH_SHORT).show();
    }
}
