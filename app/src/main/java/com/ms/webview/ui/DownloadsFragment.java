package com.ms.webview.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.App;
import com.ms.webview.R;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.MediaLibrary;
import com.ms.webview.download.DownloadNotifier;
import com.ms.webview.download.DownloadService;

public class DownloadsFragment extends Fragment implements DownloadAdapter.Actions {

    private DownloadPagerAdapter adapter;
    private ViewPager2 pager;
    private TextView tabActive;
    private TextView tabFinished;

    private ActivityResultLauncher<IntentSenderRequest> deleteConfirmation;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        deleteConfirmation = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(),
                result -> App.get().repository().refreshLibrary());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        pager = view.findViewById(R.id.downloadPager);
        tabActive = view.findViewById(R.id.tabActive);
        tabFinished = view.findViewById(R.id.tabFinished);

        adapter = new DownloadPagerAdapter(this);
        pager.setAdapter(adapter);

        // Tabs and swipe drive the same thing, so the tabs only move the pager and the page
        // callback is the single place the selection is drawn.
        tabActive.setOnClickListener(v ->
                pager.setCurrentItem(DownloadPagerAdapter.PAGE_ACTIVE, true));
        tabFinished.setOnClickListener(v ->
                pager.setCurrentItem(DownloadPagerAdapter.PAGE_FINISHED, true));

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                paintTabs(position);
            }
        });
        paintTabs(DownloadPagerAdapter.PAGE_ACTIVE);

        App.get().repository().observeAll().observe(getViewLifecycleOwner(), adapter::submit);
    }

    @Override
    public void onResume() {
        super.onResume();
        // Videos can come and go while this screen is closed — deleted from the gallery, or
        // saved by a fresh install that has only just been granted permission.
        App.get().repository().refreshLibrary();
    }

    private void paintTabs(int page) {
        style(tabActive, page == DownloadPagerAdapter.PAGE_ACTIVE);
        style(tabFinished, page == DownloadPagerAdapter.PAGE_FINISHED);
    }

    /** The selected segment is the one carrying a raised surface and the brand colour. */
    private void style(TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_segment_active : 0);
        tab.setTextColor(ContextCompat.getColor(requireContext(),
                selected ? R.color.primary : R.color.segment_inactive_text));
    }

    @Override
    public void pause(DownloadEntity d) {
        DownloadService.control(requireContext(), DownloadService.ACTION_PAUSE, d.id);
    }

    @Override
    public void resume(DownloadEntity d) {
        DownloadService.control(requireContext(), DownloadService.ACTION_RESUME, d.id);
    }

    @Override
    public void cancel(DownloadEntity d) {
        DownloadService.control(requireContext(), DownloadService.ACTION_CANCEL, d.id);
    }

    @Override
    public void delete(DownloadEntity d) {
        // Deleting removes the saved video from the gallery too, so make the user mean it.
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, d.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> performDelete(d))
                .show();
    }

    private void performDelete(DownloadEntity d) {
        // Only a transfer this app ran has a notification; a library row's negative id is not
        // a notification id and clearing it would dismiss somebody else's.
        if (!d.fromLibrary) new DownloadNotifier(requireContext()).clear(d.id);
        MediaLibrary.DeleteResult result = App.get().repository().delete(d);
        if (result.done || TextUtils.isEmpty(d.outputUri)) return;

        if (result.confirmation != null) {
            // Android owns this decision once the file is no longer ours — a reinstall is
            // enough to lose that claim — so it asks the user directly.
            try {
                deleteConfirmation.launch(
                        new IntentSenderRequest.Builder(result.confirmation).build());
                return;
            } catch (Exception ignored) {
                // Falls through to the message below.
            }
        }
        Toast.makeText(requireContext(), R.string.delete_file_failed, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void open(DownloadEntity d) {
        if (TextUtils.isEmpty(d.outputUri)) return;
        if (!App.get().repository().library().exists(d.outputUri)) {
            // Removed from the gallery behind our back. Say so, and let the refreshed list drop
            // the row rather than handing the player a uri that resolves to nothing.
            Toast.makeText(requireContext(), R.string.video_missing, Toast.LENGTH_SHORT).show();
            App.get().repository().refreshLibrary();
            return;
        }
        PlayerActivity.open(requireContext(), d.outputUri, d.title);
    }
}
