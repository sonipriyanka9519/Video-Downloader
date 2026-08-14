package com.ms.webview.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.App;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.data.MediaLibrary;
import com.ms.webview.download.DownloadNotifier;
import com.ms.webview.download.DownloadService;
import com.ms.webview.ui.downloads.DownloadAdapter;
import com.ms.webview.ui.downloads.DownloadPrefs;
import com.ms.webview.ui.downloads.DownloadSort;
import com.ms.webview.ui.guide.HowToActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything downloaded, in one list.
 *
 * <p>It was two tabs — in progress and finished — and the split cost more than it explained. A
 * download that completed while you were watching it left the page you were on for the one you were
 * not, and the question this screen exists to answer, "did the thing I just saved arrive", needed
 * both tabs to answer it. One list answers it by scrolling: whatever is still arriving is pinned to
 * the top, and everything else sits under the day it belongs to.
 */
public class DownloadsFragment extends Fragment implements DownloadAdapter.Actions {

    /** Two columns in the grid: at three, a title stops fitting on one line and starts lying. */
    private static final int GRID_COLUMNS = 2;

    private DownloadAdapter adapter;
    private RecyclerView list;
    private TextView empty;
    private ImageButton layoutToggle;

    private DownloadSort sort = DownloadSort.NEWEST;
    /** Kept so a change of order can redraw without waiting for the repository to speak again. */
    private List<DownloadEntity> all = new ArrayList<>();
    /** The row's own menu while it is up, so it can be closed with the screen. */
    @Nullable
    private BottomSheetDialog moreSheet;

    private ActivityResultLauncher<IntentSenderRequest> writeConfirmation;
    /**
     * What to do once the viewer has granted access, where anything is left to do.
     *
     * <p>A write request only grants permission — the change has to be made again afterwards. A
     * delete request from Android 11 carries the delete out itself, so that one leaves this null.
     */
    @Nullable
    private Runnable afterConfirmation;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        writeConfirmation = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    Runnable retry = afterConfirmation;
                    afterConfirmation = null;
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && retry != null) {
                        retry.run();
                    }
                    App.get().repository().refreshLibrary();
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        list = view.findViewById(R.id.downloadList);
        empty = view.findViewById(R.id.downloadEmpty);
        layoutToggle = view.findViewById(R.id.btnDownloadLayout);

        adapter = new DownloadAdapter(this);
        list.setAdapter(adapter);

        sort = DownloadPrefs.sort(requireContext());
        applyMode(DownloadPrefs.mode(requireContext()));

        layoutToggle.setOnClickListener(v -> applyMode(
                adapter.mode() == DownloadAdapter.Mode.LIST
                        ? DownloadAdapter.Mode.GRID : DownloadAdapter.Mode.LIST));
        view.findViewById(R.id.btnDownloadSort).setOnClickListener(v -> chooseSort());

        // Always there. Nothing hides it and nothing has to: it is one row at the foot of a screen
        // whose list has the rest of it, and the question it answers gets asked more than once.
        view.findViewById(R.id.btnHowTo).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), HowToActivity.class)));

        App.get().repository().observeAll().observe(getViewLifecycleOwner(), downloads -> {
            all = downloads == null ? new ArrayList<>() : downloads;
            showList();
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // Videos can come and go while this screen is closed — deleted from the gallery, or
        // saved by a fresh install that has only just been granted permission.
        App.get().repository().refreshLibrary();
    }

    @Override
    public void onDestroyView() {
        // A sheet outlives the view it was raised from, and a shown one still attached when the
        // window goes is a leaked window.
        if (moreSheet != null) moreSheet.dismiss();
        moreSheet = null;
        super.onDestroyView();
    }

    // ------------------------------------------------------------------ what is shown

    private void showList() {
        adapter.submit(requireContext(), all, sort);

        boolean nothing = all.isEmpty();
        empty.setText(R.string.no_downloads);
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        list.setVisibility(nothing ? View.GONE : View.VISIBLE);
    }

    /**
     * Rows or tiles.
     *
     * <p>The day headings span the whole width either way, which is what the span lookup is for — a
     * heading squeezed into one column of two would read as a row of the list rather than as a line
     * drawn across it.
     */
    private void applyMode(DownloadAdapter.Mode mode) {
        adapter.setMode(mode);

        if (mode == DownloadAdapter.Mode.GRID) {
            GridLayoutManager grid = new GridLayoutManager(requireContext(), GRID_COLUMNS);
            grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return adapter.isHeader(position) ? GRID_COLUMNS : 1;
                }
            });
            list.setLayoutManager(grid);
        } else {
            list.setLayoutManager(new LinearLayoutManager(requireContext()));
        }

        // The button offers the other layout rather than announcing the current one: it is a
        // switch, and a switch that shows where you are gives you nothing to press towards.
        layoutToggle.setImageResource(mode == DownloadAdapter.Mode.GRID
                ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
        DownloadPrefs.setMode(requireContext(), mode);
    }

    /**
     * What order to put the list in.
     *
     * <p>One choice at a time, because these are alternatives in a way filters are not — a list has
     * exactly one order, and offering four checkboxes would invite a combination that means nothing.
     */
    private void chooseSort() {
        DownloadSort[] options = DownloadSort.values();
        String[] labels = new String[options.length];
        int current = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = getString(options[i].label);
            if (options[i] == sort) current = i;
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sort_downloads)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    sort = options[which];
                    DownloadPrefs.setSort(requireContext(), sort);
                    showList();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------- row actions

    @Override
    public void pause(DownloadEntity d) {
        DownloadService.control(requireContext(), DownloadService.ACTION_PAUSE, d.id);
    }

    @Override
    public void resume(DownloadEntity d) {
        DownloadService.control(requireContext(), DownloadService.ACTION_RESUME, d.id);
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

    /**
     * Everything else one row can do.
     *
     * <p>Behind a menu rather than along the row, because these four differ from playing in kind:
     * playing is what the row is for, and each of these is a decision about the file. Sharing and
     * renaming and reading its details are only offered for a video that is actually there —
     * a transfer still running has nothing to share and no name of its own yet.
     */
    @Override
    public void more(DownloadEntity d) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.sheet_download_more, null, false);

        ((TextView) content.findViewById(R.id.moreName))
                .setText(TextUtils.isEmpty(d.fileName) ? d.title : d.fileName);

        boolean saved = d.status == DownloadStatus.COMPLETED && !TextUtils.isEmpty(d.outputUri);
        View share = content.findViewById(R.id.moreShare);
        View rename = content.findViewById(R.id.moreRename);
        share.setVisibility(saved ? View.VISIBLE : View.GONE);
        rename.setVisibility(saved ? View.VISIBLE : View.GONE);

        moreSheet = new BottomSheetDialog(requireContext());
        moreSheet.setContentView(content);

        share.setOnClickListener(v -> {
            dismissMore();
            share(d);
        });
        rename.setOnClickListener(v -> {
            dismissMore();
            askNewName(d);
        });
        content.findViewById(R.id.moreProperty).setOnClickListener(v -> {
            dismissMore();
            showProperties(d);
        });
        content.findViewById(R.id.moreDelete).setOnClickListener(v -> {
            dismissMore();
            confirmDelete(d);
        });

        moreSheet.show();
    }

    private void dismissMore() {
        if (moreSheet != null) moreSheet.dismiss();
        moreSheet = null;
    }

    /**
     * Hands the video to whatever the viewer picks.
     *
     * <p>A chooser every time, never a remembered target. Which app a video should go to is a
     * decision about that video, and this app has no business having an opinion about it.
     */
    private void share(DownloadEntity d) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(TextUtils.isEmpty(d.mime) ? "video/*" : d.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(d.outputUri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Asks for a new name, starting from the one it has.
     *
     * <p>Without the extension, and the extension is put back by the library: it is not part of
     * what anyone means by the name of a video, and a typed ".mp4" that replaced a real ".mkv"
     * would leave a file nothing plays.
     */
    private void askNewName(DownloadEntity d) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_rename, null, false);
        EditText input = content.findViewById(R.id.renameInput);
        input.setText(d.title);
        input.setSelection(input.getText().length());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.rename)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.rename, (dialog, which) ->
                        performRename(d, input.getText().toString(), true))
                .show();
    }

    /**
     * @param mayAsk false on the second attempt, so a refusal cannot put the same request up
     *               again and again
     */
    private void performRename(DownloadEntity d, String newName, boolean mayAsk) {
        if (TextUtils.isEmpty(newName.trim())) return;

        MediaLibrary.WriteResult result = App.get().repository().rename(d, newName);
        if (result.done) return;

        // Granting access is not the rename; it only makes the rename possible. So the retry is
        // queued before the request goes up, and runs when the viewer comes back having agreed.
        if (mayAsk && result.confirmation != null) {
            afterConfirmation = () -> performRename(d, newName, false);
            if (launchConfirmation(result)) return;
            afterConfirmation = null;
        }
        Toast.makeText(requireContext(), R.string.rename_failed, Toast.LENGTH_SHORT).show();
    }

    /** Everything the app knows about one file, in the order somebody would ask for it. */
    private void showProperties(DownloadEntity d) {
        StringBuilder body = new StringBuilder();
        append(body, R.string.property_name, TextUtils.isEmpty(d.fileName) ? d.title : d.fileName);
        append(body, R.string.property_size, Formats.bytes(
                d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes));
        if (d.durationMs > 0) {
            append(body, R.string.property_duration, Formats.duration(d.durationMs));
        }
        if (!TextUtils.isEmpty(d.quality)) {
            append(body, R.string.property_quality, d.quality);
        }
        append(body, R.string.property_type, TextUtils.isEmpty(d.mime) ? "video" : d.mime);

        long when = d.completedAt > 0 ? d.completedAt : d.createdAt;
        if (when > 0) {
            append(body, R.string.property_date, DateUtils.formatDateTime(requireContext(), when,
                    DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                            | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_YEAR));
        }
        if (!TextUtils.isEmpty(d.pageUrl)) {
            append(body, R.string.property_source, d.pageUrl);
        }
        // The address the media itself came from, which is the one that answers "why has this no
        // sound" — a video-only rendition and a complete file look identical once saved.
        if (!TextUtils.isEmpty(d.sourceUrl)) {
            append(body, R.string.property_stream, d.sourceUrl);
        }
        if (!TextUtils.isEmpty(d.outputUri)) {
            append(body, R.string.property_location, d.outputUri);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.property)
                .setMessage(body.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void append(StringBuilder body, int label, String value) {
        body.append(getString(label)).append('\n').append(value).append("\n\n");
    }

    private void confirmDelete(DownloadEntity d) {
        // Deleting removes the saved video from the gallery too, so make the user mean it.
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, d.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> performDelete(d, true))
                .show();
    }

    /**
     * @param mayAsk false on the second attempt, so a refusal cannot loop
     */
    private void performDelete(DownloadEntity d, boolean mayAsk) {
        // A transfer still running is cancelled rather than deleted: there is no file yet, and
        // the engine has to be told to stop before its scratch space can be swept.
        if (!d.fromLibrary && !d.status.terminal()) {
            DownloadService.control(requireContext(), DownloadService.ACTION_CANCEL, d.id);
        }
        // Only a transfer this app ran has a notification; a library row's negative id is not
        // a notification id and clearing it would dismiss somebody else's.
        if (!d.fromLibrary) new DownloadNotifier(requireContext()).clear(d.id);

        MediaLibrary.WriteResult result = App.get().repository().delete(d);
        if (result.done || TextUtils.isEmpty(d.outputUri)) return;

        if (mayAsk && result.confirmation != null) {
            // From Android 11 the system's own delete request carries the delete out on approval,
            // so there is nothing to run afterwards. Before that it only granted access, and the
            // delete still has to be made.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                afterConfirmation = () -> performDelete(d, false);
            }
            if (launchConfirmation(result)) return;
            afterConfirmation = null;
        }
        Toast.makeText(requireContext(), R.string.delete_file_failed, Toast.LENGTH_SHORT).show();
    }

    /**
     * Hands a refused write back to the system to ask about.
     *
     * <p>Android owns this decision once the file is no longer ours — a reinstall is enough to lose
     * that claim — so it asks the user directly rather than letting us act on their behalf.
     */
    private boolean launchConfirmation(MediaLibrary.WriteResult result) {
        try {
            writeConfirmation.launch(
                    new IntentSenderRequest.Builder(result.confirmation).build());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
