package com.ms.webview.data;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;

import com.ms.webview.download.DownloadService;
import com.ms.webview.download.DownloadThumbnails;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The downloads list, assembled from the only two places that know anything: transfers still
 * running, and videos already on the device.
 *
 * <p>A content observer keeps the second half honest. Delete a video from the gallery and the
 * row disappears here too — which is the real fix for a list that used to offer to play files
 * that were no longer there.
 */
public class DownloadRepository {

    private final Context context;
    private final DownloadStore store;
    private final MediaLibrary library;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<DownloadEntity>> saved = new MutableLiveData<>();
    private final MediatorLiveData<List<DownloadEntity>> combined = new MediatorLiveData<>();

    public DownloadRepository(Context context) {
        this.context = context.getApplicationContext();
        this.store = DownloadStore.get(context);
        this.library = new MediaLibrary(context);

        combined.addSource(store.live(), value -> recombine());
        combined.addSource(saved, value -> recombine());

        watchLibrary();
        refreshLibrary();
    }

    public DownloadStore store() {
        return store;
    }

    public MediaLibrary library() {
        return library;
    }

    public ExecutorService io() {
        return io;
    }

    public LiveData<List<DownloadEntity>> observeAll() {
        return combined;
    }

    /** Unfinished transfers first, then the saved videos newest-first. */
    private void recombine() {
        List<DownloadEntity> merged = new ArrayList<>();
        List<DownloadEntity> active = store.live().getValue();
        List<DownloadEntity> finished = saved.getValue();
        if (active != null) merged.addAll(active);
        if (finished != null) merged.addAll(finished);
        combined.setValue(merged);
    }

    public void refreshLibrary() {
        io.execute(() -> saved.postValue(library.saved()));
    }

    /**
     * Re-reads the library whenever the video collection changes, so downloads finishing,
     * gallery deletions and files added by another app all land here without a manual refresh.
     */
    private void watchLibrary() {
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, @Nullable Uri uri) {
                refreshLibrary();
            }
        };
        try {
            context.getContentResolver()
                    .registerContentObserver(MediaLibrary.collection(), true, observer);
        } catch (Exception ignored) {
            // Without the observer the list still refreshes when the screen is opened.
        }
    }

    /**
     * Removes a row and whatever it stands for: a saved video goes from the device, an
     * unfinished transfer gives up its scratch space.
     *
     * @return a confirmation the caller must launch, when the system will not delete silently
     */
    public MediaLibrary.WriteResult delete(DownloadEntity d) {
        if (d == null) return new MediaLibrary.WriteResult(false, null);

        if (!d.fromLibrary) {
            store.delete(d.id);
            // An abandoned HLS run can leave hundreds of segment files; sweeping them is not
            // something the tap that removed the row should wait for.
            final String temp = d.tempPath;
            final long id = d.id;
            io.execute(() -> {
                if (!TextUtils.isEmpty(temp)) DownloadService.deleteTree(new File(temp));
                new DownloadThumbnails(context).delete(id);
            });
        }

        // Synchronous, because a delete the system wants confirmed hands back an IntentSender
        // that the caller has to launch from the tap that is still in progress.
        MediaLibrary.WriteResult result = library.delete(d.outputUri);
        refreshLibrary();
        return result;
    }

    /**
     * Gives a finished video a new name.
     *
     * <p>Synchronous for the same reason a delete is: a write the system wants confirmed hands
     * back an IntentSender, and that has to reach the caller while the tap that asked for it is
     * still in progress.
     */
    public MediaLibrary.WriteResult rename(DownloadEntity d, String newName) {
        if (d == null) return new MediaLibrary.WriteResult(false, null);

        MediaLibrary.WriteResult result = library.rename(d.outputUri, newName, d.fileName);
        // The list is rebuilt from MediaStore, so the new name arrives with the refresh rather
        // than being patched into the row we happen to be holding.
        refreshLibrary();
        return result;
    }

    /** Called once at startup: nothing can still be running if the process just started. */
    public void reconcileOnStartup() {
        io.execute(() -> {
            store.reconcileOnStartup();
            refreshLibrary();
        });
    }
}
