package com.ms.webview.download;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.google.gson.Gson;
import com.ms.webview.App;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadStore;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.detect.MediaItem;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.MediaVariant;
import com.ms.webview.detect.UrlClassifier;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns every running transfer.
 *
 * <p>A foreground service rather than WorkManager: downloads need pause/resume, live speed and
 * per-chunk checkpointing, none of which fit WorkManager's opaque scheduling and execution
 * window.
 */
public class DownloadService extends Service implements DownloadTask.Listener {

    private static final String TAG = "DownloadService";

    public static final String ACTION_ENQUEUE = "com.ms.webview.ENQUEUE";
    public static final String ACTION_PAUSE = "com.ms.webview.PAUSE";
    public static final String ACTION_RESUME = "com.ms.webview.RESUME";
    public static final String ACTION_CANCEL = "com.ms.webview.CANCEL";

    public static final String EXTRA_ID = "id";
    private static final String EXTRA_URL = "url";
    private static final String EXTRA_TITLE = "title";
    private static final String EXTRA_POSTER = "poster";
    private static final String EXTRA_PAGE = "page";
    private static final String EXTRA_MIME = "mime";
    private static final String EXTRA_QUALITY = "quality";
    private static final String EXTRA_SIZE = "size";
    private static final String EXTRA_RANGES = "ranges";
    private static final String EXTRA_HEADERS = "headers";
    private static final String EXTRA_KIND = "kind";
    private static final String EXTRA_AUDIO = "audio";

    /** Three at once keeps the pipe full without starving the page the user is still browsing. */
    private static final int MAX_PARALLEL_DOWNLOADS = 3;

    private final ConcurrentHashMap<Long, DownloadTask> active = new ConcurrentHashMap<>();
    private final ExecutorService taskPool = Executors.newFixedThreadPool(MAX_PARALLEL_DOWNLOADS);
    private final ExecutorService commands = Executors.newSingleThreadExecutor();

    private DownloadNotifier notifier;
    private DownloadStore store;

    public static void enqueue(Context context, MediaItem item, MediaVariant variant) {
        Intent i = new Intent(context, DownloadService.class)
                .setAction(ACTION_ENQUEUE)
                .putExtra(EXTRA_URL, variant.url)
                .putExtra(EXTRA_TITLE, item.displayTitle())
                .putExtra(EXTRA_POSTER, item.persistableThumbnail())
                .putExtra(EXTRA_PAGE, item.pageUrl)
                .putExtra(EXTRA_MIME, variant.mime)
                .putExtra(EXTRA_QUALITY, Formats.quality(variant.width, variant.height))
                .putExtra(EXTRA_SIZE, variant.sizeBytes)
                .putExtra(EXTRA_RANGES, variant.acceptsRanges)
                .putExtra(EXTRA_KIND, variant.kind.name())
                .putExtra(EXTRA_AUDIO, variant.audioUrl)
                .putExtra(EXTRA_HEADERS, new Gson().toJson(variant.headers));
        ContextCompat.startForegroundService(context, i);
    }

    public static void control(Context context, String action, long id) {
        Intent i = new Intent(context, DownloadService.class)
                .setAction(action)
                .putExtra(EXTRA_ID, id);
        ContextCompat.startForegroundService(context, i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifier = new DownloadNotifier(this);
        store = DownloadStore.get(this);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Must happen immediately: the system gives a foreground service only a few seconds.
        goForeground();

        if (intent == null || intent.getAction() == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }

        final String action = intent.getAction();
        final long id = intent.getLongExtra(EXTRA_ID, -1);
        final Intent snapshot = new Intent(intent);

        commands.execute(() -> {
            try {
                switch (action) {
                    case ACTION_ENQUEUE:
                        handleEnqueue(snapshot);
                        break;
                    case ACTION_PAUSE:
                        handlePause(id);
                        break;
                    case ACTION_RESUME:
                        handleResume(id);
                        break;
                    case ACTION_CANCEL:
                        handleCancel(id);
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                Log.e(TAG, "Command " + action + " failed", e);
            }
            stopIfIdle();
        });

        return START_NOT_STICKY;
    }

    private void goForeground() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        ServiceCompat.startForeground(this, DownloadNotifier.SUMMARY_ID,
                notifier.summary(Math.max(1, active.size()), getString(com.ms.webview.R.string.preparing)),
                type);
    }

    private void handleEnqueue(Intent intent) {
        String url = intent.getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(url)) return;

        DownloadEntity existing = store.findBySource(url);
        if (existing != null && (existing.status.active() || existing.status == DownloadStatus.COMPLETED)) {
            Log.i(TAG, "Already queued or finished: " + url);
            return;
        }

        MediaKind kind = kindOf(intent.getStringExtra(EXTRA_KIND));

        DownloadEntity d = new DownloadEntity();
        d.sourceUrl = url;
        d.kind = kind;
        d.audioUrl = intent.getStringExtra(EXTRA_AUDIO);
        d.title = orEmpty(intent.getStringExtra(EXTRA_TITLE), Formats.lastPathSegment(url));
        d.posterUrl = intent.getStringExtra(EXTRA_POSTER);
        d.pageUrl = intent.getStringExtra(EXTRA_PAGE);
        d.mime = intent.getStringExtra(EXTRA_MIME);
        d.quality = intent.getStringExtra(EXTRA_QUALITY);
        d.totalBytes = intent.getLongExtra(EXTRA_SIZE, 0);
        d.acceptsRanges = intent.getBooleanExtra(EXTRA_RANGES, false);
        d.headersJson = orEmpty(intent.getStringExtra(EXTRA_HEADERS), "{}");
        d.createdAt = System.currentTimeMillis();
        d.status = DownloadStatus.QUEUED;

        String ext = UrlClassifier.extensionFor(kind, d.mime, url);
        d.fileName = Formats.fileName(d.title, url, ext);
        // Progressive downloads use a single .part file; HLS and DASH need a working directory
        // for their segments and separate tracks.
        String stem = UUID.randomUUID().toString();
        boolean needsDirectory = kind == MediaKind.HLS || kind == MediaKind.DASH;
        d.tempPath = new File(partialsDir(), needsDirectory ? stem : stem + ".part")
                .getAbsolutePath();

        d.id = store.insert(d);

        // Move any detection-time frame out of the cache, so the row has a poster while it
        // downloads rather than only once it finishes.
        String persisted = new DownloadThumbnails(this).persist(d.posterUrl, d.id);
        if (persisted == null || !persisted.equals(d.posterUrl)) {
            d.posterUrl = persisted;
            store.update(d);
        }

        notifier.showProgress(d, 0);
        submit(d.id);
    }

    private static MediaKind kindOf(String name) {
        if (name == null) return MediaKind.PROGRESSIVE;
        try {
            MediaKind kind = MediaKind.valueOf(name);
            return kind.downloadable() ? kind : MediaKind.PROGRESSIVE;
        } catch (IllegalArgumentException e) {
            return MediaKind.PROGRESSIVE;
        }
    }

    private void handleResume(long id) {
        if (id < 0 || active.containsKey(id)) return;
        DownloadEntity d = store.byId(id);
        if (d == null || d.status == DownloadStatus.COMPLETED) return;
        d.status = DownloadStatus.QUEUED;
        d.error = null;
        store.update(d);
        notifier.showProgress(d, 0);
        submit(id);
    }

    private void handlePause(long id) {
        DownloadTask task = active.get(id);
        if (task != null) {
            task.pause();
            return;
        }
        DownloadEntity d = store.byId(id);
        if (d != null && d.status.active()) {
            d.status = DownloadStatus.PAUSED;
            store.update(d);
            notifier.showProgress(d, 0);
        }
    }

    private void handleCancel(long id) {
        DownloadTask task = active.get(id);
        if (task != null) {
            task.cancel();
            return;
        }
        DownloadEntity d = store.byId(id);
        if (d != null) {
            if (!TextUtils.isEmpty(d.tempPath)) deleteTree(new File(d.tempPath));
            store.deleteChunks(id);
            store.deleteSegments(id);
            d.status = DownloadStatus.CANCELLED;
            store.update(d);
        }
        notifier.clear(id);
    }

    /** Handles both shapes of scratch space: a single .part file, or an HLS segment directory. */
    public static void deleteTree(File path) {
        if (path == null || !path.exists()) return;
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children != null) {
                for (File child : children) deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        path.delete();
    }

    private void submit(long id) {
        DownloadEntity entity = store.byId(id);
        MediaKind kind = entity == null ? MediaKind.PROGRESSIVE : entity.kind;

        // A video file with its audio in a second file needs both fetched and muxed, whatever
        // kind it was labelled. Chosen on the audio rather than the label because the label
        // describes where the address came from, not whether the file is complete on its own —
        // and the plain downloader ignores the audio address entirely, so anything that reached
        // it with one saved as a video with no sound.
        boolean needsMux = entity != null && !TextUtils.isEmpty(entity.audioUrl);

        DownloadTask task;
        if (kind == MediaKind.HLS) {
            task = new HlsDownloader(this, store, id, this);
        } else if (kind == MediaKind.DASH || needsMux) {
            task = new DashDownloader(this, store, id, this);
        } else {
            task = new ProgressiveDownloader(this, store, id, this);
        }
        active.put(id, task);
        taskPool.execute(() -> {
            try {
                task.run();
            } finally {
                active.remove(id);
                stopIfIdle();
            }
        });
    }

    @Override
    public void onProgress(DownloadEntity entity, long bytesPerSecond) {
        DownloadSpeeds.record(entity.id, bytesPerSecond);
        notifier.showProgress(entity, bytesPerSecond);
        notifier.updateSummary(Math.max(1, active.size()), entity.title);
    }

    @Override
    public void onFinished(DownloadEntity entity) {
        DownloadSpeeds.clear(entity.id);
        if (entity.status == DownloadStatus.CANCELLED) {
            notifier.clear(entity.id);
        } else if (entity.status == DownloadStatus.PAUSED) {
            notifier.showProgress(entity, 0);
        } else {
            notifier.showFinished(entity);
        }
        if (entity.status == DownloadStatus.COMPLETED) {
            // The file is the record now. Asking the library to re-read means the row appears
            // in Finished without waiting on the content observer.
            App app = App.get();
            if (app != null) app.repository().refreshLibrary();
        }
    }

    private void stopIfIdle() {
        if (!active.isEmpty()) return;
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private File partialsDir() {
        File base = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) base = getFilesDir();
        File dir = new File(base, "partials");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static String orEmpty(String value, String fallback) {
        return TextUtils.isEmpty(value) ? fallback : value;
    }

    @Override
    public void onDestroy() {
        for (DownloadTask task : active.values()) task.pause();
        taskPool.shutdownNow();
        commands.shutdownNow();
        // Whatever was mid-transfer is now nobody's: leave it paused rather than running, so
        // the next start offers to resume it instead of showing a download that never moves.
        store.reconcileOnStartup();
        super.onDestroy();
    }
}
