package com.ms.webview.ui.lock;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.App;
import com.ms.webview.R;
import com.ms.webview.ui.SystemBars;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.ui.Snacks;
import com.ms.webview.ui.downloads.DownloadAdapter;
import com.ms.webview.ui.downloads.DownloadSort;
import com.ms.webview.ui.downloads.PrivateStore;
import com.ms.webview.ads.AdIds;
import com.ms.webview.ads.Interstitials;
import com.ms.webview.ads.NativeAds;
import com.ms.webview.ui.PlayerActivity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The private library — screen 11, panels D and E.
 *
 * <p>Its own screen rather than a filter on the ordinary one, and the ink wash in the header
 * says so at a glance. The design is firm about this and the reason is practical: moving a video
 * the wrong way is the mistake that has to be impossible to blunder into, and a chip you might
 * not have noticed is not enough of a signpost.
 *
 * <p>The rows are the library's own. A private video is still a video, and giving it a different
 * row would make it look like a different kind of thing.
 */
public class PrivateFolderActivity extends AppCompatActivity implements DownloadAdapter.Actions {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private DownloadAdapter adapter;

    /** The in-page ad, held so it can be released with the screen. */
    private android.view.ViewGroup adSlot;
    private View empty;
    /** Row id back to the private item it stands for, so an action knows what it is acting on. */
    private final Map<Long, PrivateStore.Item> byRow = new HashMap<>();

    @Nullable
    private BottomSheetDialog sheet;

    /**
     * Set when this screen is the one starting the next: playing a private video is not leaving the
     * folder, and closing it underneath the player would drop the viewer back into the library when
     * they pressed back.
     */
    private boolean expectReturn;

    /**
     * Only ever from behind a challenge — see {@link PrivateAuth}, which is what every caller uses.
     * There is no route to this screen that does not pass through it.
     */
    public static void open(Context context) {
        context.startActivity(new Intent(context, PrivateFolderActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Keeps private titles and thumbnails out of the recents screenshot, which is a picture of
        // this screen the system keeps and shows to whoever presses the button next.
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_private_folder);
        empty = findViewById(R.id.privateEmpty);
        SystemBars.pad(findViewById(R.id.privateRoot));
        findViewById(R.id.btnPrivateBack).setOnClickListener(v -> finish());

        adapter = new DownloadAdapter(this);
        androidx.recyclerview.widget.RecyclerView list = findViewById(R.id.privateList);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        adSlot = findViewById(R.id.privateAdSlot);

        // The list is built in onResume, not here — and so is the ad, which follows it. See below.
    }

    /**
     * Rebuilt on every return, not only on first open.
     *
     * <p>Built once in onCreate, this screen never noticed anything that happened while it was
     * away: play a private video, come back, and the row still showed no progress bar because
     * nothing had asked the store again. The watched fraction is read at bind time, so a rebind
     * is all it takes — and the player is the only way back onto this screen anyway.
     */
    @Override
    protected void onResume() {
        super.onResume();
        refresh();

        // What the player owed on the way out. MainActivity pays the same debt, but a private
        // video comes back here instead, and the ad would otherwise wait until the viewer
        // happened to reach the library.
        //
        // expectReturn because onStop closes this folder when anything covers it: an
        // interstitial would finish the screen underneath itself, and the viewer would dismiss
        // the ad onto the library rather than back into the folder they were standing in.
        if (Interstitials.showIfQueued(this)) expectReturn = true;
    }

    /**
     * Leaving the app closes the folder.
     *
     * <p>The lock is asked for once, in front of this screen. Left standing while the app is in the
     * background, that one answer would still be good tomorrow — so the screen goes instead, and
     * coming back means unlocking again. onStop rather than onPause: the system's own biometric
     * sheet and the delete consent dialog both pause this without anybody having left.
     */
    @Override
    protected void onStop() {
        super.onStop();
        boolean ownScreen = expectReturn;
        expectReturn = false;
        if (!isChangingConfigurations() && !ownScreen) finish();
    }

    @Override
    protected void onDestroy() {
        NativeAds.destroy(adSlot);
        io.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        List<PrivateStore.Item> items = PrivateStore.all(this);
        byRow.clear();

        List<DownloadEntity> rows = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            DownloadEntity entity = PrivateStore.asEntity(this, items.get(i));
            // Numbered here rather than hashed from the item's id. A hash can collide, and two
            // rows sharing an id means the ⋮ acts on the wrong video — which on this screen
            // could delete the wrong one.
            entity.id = -(i + 1L);
            byRow.put(entity.id, items.get(i));
            rows.add(entity);
        }

        // Newest first, which is the order the store already keeps them in. The day headings
        // that come with a date sort are the library's own and say when each video was hidden —
        // harmless behind the lock, and the price of using the library's rows unchanged.
        adapter.submit(this, rows, DownloadSort.NEWEST);
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);

        // No ad over an empty folder. Panel D is the only place that explains what this folder is
        // for, and an advert above that explanation would be the largest thing on a screen with
        // nothing of the viewer's on it - on the one screen in the app where that reads worst.
        //
        // Destroyed rather than hidden when the last video leaves: a hidden slot still holds a
        // NativeAd, and this runs on every resume.
        if (rows.isEmpty()) NativeAds.destroy(adSlot);
        else NativeAds.load(this, adSlot, AdIds.nativeAd());
    }

    // ------------------------------------------------------------------ row actions

    @Override
    public void open(DownloadEntity d) {
        // No queue. Handing the player a private queue would put private titles on a sheet that
        // outlives this screen.
        expectReturn = true;
        PlayerActivity.open(this, d.outputUri, d.title);
    }

    @Override
    public void more(DownloadEntity d) {
        PrivateStore.Item item = byRow.get(d.id);
        if (item == null) return;

        View content = LayoutInflater.from(this)
                .inflate(R.layout.sheet_private_item, null, false);
        ((TextView) content.findViewById(R.id.privateName)).setText(item.fileName);

        sheet = new BottomSheetDialog(this, R.style.ThemeOverlay_Ds_BottomSheet);
        sheet.setContentView(content);

        content.findViewById(R.id.privatePlay).setOnClickListener(v -> {
            dismissSheet();
            open(d);
        });
        content.findViewById(R.id.privateMoveOut).setOnClickListener(v -> {
            dismissSheet();
            moveOut(item);
        });
        content.findViewById(R.id.privateDelete).setOnClickListener(v -> {
            dismissSheet();
            confirmDelete(item);
        });
        sheet.show();
    }

    /**
     * Puts a video back where everything else can see it.
     *
     * <p>Off the main thread, because it is a real file copy into MediaStore. The row goes only
     * once the copy has returned a uri.
     */
    private void moveOut(PrivateStore.Item item) {
        io.execute(() -> {
            String uri = PrivateStore.moveOut(this, item);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;

                if (uri == null) {
                    snack(getString(R.string.private_move_failed));
                    return;
                }
                // The library has a new file it does not know about yet.
                App.get().repository().refreshLibrary();
                refresh();
                snack(getString(R.string.private_moved_out, item.title));
            });
        });
    }

    /**
     * Deleting from here is the one destructive thing on this screen, so it asks.
     *
     * <p>Moving out does not ask — nothing is destroyed by it and it is undone by moving back in.
     * The design's rule, and the distinction is worth keeping sharp.
     */
    private void confirmDelete(PrivateStore.Item item) {
        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, item.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    PrivateStore.delete(this, item);
                    refresh();
                })
                .show();
    }

    private void dismissSheet() {
        if (sheet != null) sheet.dismiss();
        sheet = null;
    }

    private void snack(CharSequence text) {
        Snacks.make(findViewById(R.id.privateRoot), text, 3000, null).show();
    }

    // Nothing here is ever in flight, so none of these can be reached.

    @Override
    public void pause(DownloadEntity d) {
    }

    @Override
    public void resume(DownloadEntity d) {
    }

    @Override
    public void cancel(DownloadEntity d) {
    }

    @Override
    public void onSelectionChanged(int count, boolean selecting) {
        // Select mode is not offered here: the bar's actions are share, file and delete, and
        // sharing a private video out of the folder is the one thing this screen must not make
        // easy to do by accident.
        if (selecting) adapter.clearSelection();
    }
}
