package com.ms.webview.download;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Errors;
import com.ms.webview.core.Http;
import com.ms.webview.data.DownloadStore;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads one DASH rendition.
 *
 * <p>In the shape Instagram and Facebook publish, each Representation is a complete file — a
 * video-only MP4 alongside a separate audio track — so this is two straight downloads and a
 * mux, with no segment assembly. That is also what makes their quality ladder usable at all:
 * every rung is an independently fetchable file.
 */
public class DashDownloader implements DownloadTask {

    private static final String TAG = "DashDownloader";
    private static final int BUFFER = 64 * 1024;
    private static final long PROGRESS_INTERVAL_MS = 700;

    private final Context context;
    private final DownloadStore store;
    private final long downloadId;
    private final Listener listener;

    private final AtomicLong bytes = new AtomicLong();
    private volatile Call currentCall;
    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;

    private Map<String, String> headers;

    public DashDownloader(Context context, DownloadStore store, long downloadId, Listener listener) {
        this.context = context.getApplicationContext();
        this.store = store;
        this.downloadId = downloadId;
        this.listener = listener;
    }

    @Override
    public long downloadId() {
        return downloadId;
    }

    @Override
    public void pause() {
        pauseRequested = true;
        cancelCall();
    }

    @Override
    public void cancel() {
        cancelRequested = true;
        cancelCall();
    }

    private void cancelCall() {
        Call call = currentCall;
        if (call != null) {
            try {
                call.cancel();
            } catch (Exception ignored) {
            }
        }
    }

    private boolean stopping() {
        return pauseRequested || cancelRequested;
    }

    @Override
    public void run() {
        DownloadEntity d = store.byId(downloadId);
        if (d == null) return;

        if (cancelRequested) {
            finish(d, DownloadStatus.CANCELLED, null);
            return;
        }

        d.status = DownloadStatus.RUNNING;
        d.error = null;
        headers = d.headers();
        store.update(d);
        listener.onProgress(d, 0);

        File workDir = new File(d.tempPath);
        try {
            if (!workDir.exists() && !workDir.mkdirs()) {
                throw new IOException("Cannot create " + workDir);
            }

            // Both tracks first, so a failure on the second does not leave a silent video in
            // the gallery.
            File video = new File(workDir, "video.mp4");
            fetch(d, d.sourceUrl, video);

            File audio = null;
            if (!TextUtils.isEmpty(d.audioUrl)) {
                audio = new File(workDir, "audio.m4a");
                fetch(d, d.audioUrl, audio);
            }

            if (cancelRequested) {
                DownloadService.deleteTree(workDir);
                finish(d, DownloadStatus.CANCELLED, null);
                return;
            }
            if (pauseRequested) {
                // Partial files are not resumable here, so a pause restarts the fetch.
                DownloadService.deleteTree(workDir);
                d.downloadedBytes = 0;
                finish(d, DownloadStatus.PAUSED, null);
                return;
            }

            d.status = DownloadStatus.PUBLISHING;
            d.totalBytes = bytes.get();
            d.downloadedBytes = bytes.get();
            d.totalEstimated = false;
            store.update(d);
            listener.onProgress(d, 0);

            File output = new File(workDir, "output.mp4");
            Remuxer.remux(video, audio, output);
            d.mime = "video/mp4";

            DownloadThumbnails.Result finished =
                    new DownloadThumbnails(context).inspect(output, downloadId);
            if (!finished.playable()) {
                throw new IOException("Muxed file is not a playable video");
            }
            if (finished.posterPath != null) d.posterUrl = finished.posterPath;

            String published = new MediaStorePublisher(context)
                    .publish(output, d.fileName, d.mime);

            d.outputUri = published;
            d.completedAt = System.currentTimeMillis();
            DownloadService.deleteTree(workDir);
            finish(d, DownloadStatus.COMPLETED, null);

        } catch (Exception e) {
            Log.e(TAG, "DASH download " + downloadId + " failed", e);
            if (cancelRequested) {
                DownloadService.deleteTree(workDir);
                finish(d, DownloadStatus.CANCELLED, null);
            } else if (pauseRequested) {
                finish(d, DownloadStatus.PAUSED, null);
            } else {
                finish(d, DownloadStatus.FAILED, Errors.friendly(e));
            }
        }
    }

    private void fetch(DownloadEntity d, String url, File target) throws IOException {
        try {
            stream(d, url, target, headers);
        } catch (DeniedException denied) {
            Map<String, String> relaxed = Http.relaxed(headers);
            try {
                stream(d, url, target, relaxed);
                headers = relaxed;
            } catch (DeniedException stillDenied) {
                throw new IOException(Errors.forStatus(stillDenied.code));
            }
        }
    }

    private static class DeniedException extends IOException {
        final int code;

        DeniedException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private void stream(DownloadEntity d, String url, File target, Map<String, String> requestHeaders)
            throws IOException {
        Request.Builder rb = new Request.Builder().url(url).get();
        Http.withCaptured(rb, requestHeaders);
        rb.header("Accept-Encoding", "identity");

        Call call = Http.client().newCall(rb.build());
        currentCall = call;
        long startedAt = bytes.get();

        try (Response resp = call.execute()) {
            int code = resp.code();
            if (Http.deniedByHeaders(code)) throw new DeniedException(code);
            if (code != 200 && code != 206) throw new IOException(Errors.forStatus(code));

            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty response body");

            long lastReport = System.currentTimeMillis();
            long lastBytes = bytes.get();

            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    if (stopping()) return;
                    out.write(buffer, 0, read);
                    long total = bytes.addAndGet(read);

                    long now = System.currentTimeMillis();
                    if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                        long speed = (total - lastBytes) * 1000 / Math.max(1, now - lastReport);
                        lastReport = now;
                        lastBytes = total;
                        d.downloadedBytes = total;
                        store.updateProgress(downloadId, total);
                        listener.onProgress(d, Math.max(0, speed));
                    }
                }
            }
        } catch (IOException e) {
            // Roll the counter back so a retry with different headers does not double count.
            bytes.set(startedAt);
            throw e;
        } finally {
            currentCall = null;
        }
    }

    private void finish(DownloadEntity d, DownloadStatus status, @Nullable String error) {
        d.status = status;
        d.error = error;
        store.update(d);
        listener.onFinished(d);
    }
}
