package com.ms.webview.download;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Errors;
import com.ms.webview.core.Http;
import com.ms.webview.data.DownloadStore;
import com.ms.webview.data.ChunkEntity;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.Prober;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads one self-contained media file over several HTTP range connections.
 *
 * <p>Per-chunk byte progress is persisted as it goes, so a download interrupted by a process
 * kill resumes from where it stopped rather than starting over.
 */
public class ProgressiveDownloader implements DownloadTask {

    private static final String TAG = "ProgressiveDownloader";

    private static final int MAX_CONNECTIONS = 6;
    private static final long MIN_CHUNK_BYTES = 2L * 1024 * 1024;
    private static final long PERSIST_INTERVAL_MS = 700;
    private static final int BUFFER = 64 * 1024;

    /** Live byte count kept outside the entity so worker and reporter never race on a long. */
    private static class ChunkState {
        final ChunkEntity entity;
        final AtomicLong done;

        ChunkState(ChunkEntity entity) {
            this.entity = entity;
            this.done = new AtomicLong(entity.downloadedBytes);
        }

        long cursor() {
            return entity.startByte + done.get();
        }

        boolean complete() {
            return entity.endByte != Long.MAX_VALUE && done.get() >= entity.size();
        }
    }

    private final Context context;
    private final DownloadStore store;
    private final long downloadId;
    private final DownloadTask.Listener listener;

    private final AtomicLong downloaded = new AtomicLong();
    private final CopyOnWriteArrayList<Call> inFlight = new CopyOnWriteArrayList<>();

    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;

    public ProgressiveDownloader(Context context, DownloadStore store, long downloadId,
                                 DownloadTask.Listener listener) {
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
        cancelCalls();
    }

    @Override
    public void cancel() {
        cancelRequested = true;
        cancelCalls();
    }

    private void cancelCalls() {
        for (Call c : inFlight) {
            try {
                c.cancel();
            } catch (Exception ignored) {
            }
        }
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
        store.update(d);
        listener.onProgress(d, 0);

        try {
            File temp = new File(d.tempPath);
            File parent = temp.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                throw new IOException("Cannot create " + parent);
            }

            if (d.totalBytes <= 0) {
                Prober.Result r = new Prober().probe(d.sourceUrl, d.headers());
                if (r != null) {
                    if (r.contentLength > 0) d.totalBytes = r.contentLength;
                    d.acceptsRanges = r.acceptsRanges;
                    if (r.mime != null) d.mime = r.mime;
                    store.update(d);
                }
            }

            List<ChunkState> chunks = loadOrPlan(d, temp);
            boolean ok = transfer(d, temp, chunks, chunks.size() > 1);

            if (!ok && !pauseRequested && !cancelRequested && chunks.size() > 1) {
                // Some servers advertise ranges and then ignore them. Retry as one stream.
                Log.w(TAG, "Multi-connection transfer failed, retrying single-stream");
                store.deleteChunks(downloadId);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                downloaded.set(0);
                d.acceptsRanges = false;
                d.error = null;
                store.update(d);
                chunks = loadOrPlan(d, temp);
                ok = transfer(d, temp, chunks, false);
            }

            persist(d, chunks);

            if (cancelRequested) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                store.deleteChunks(downloadId);
                finish(d, DownloadStatus.CANCELLED, null);
                return;
            }
            if (pauseRequested) {
                finish(d, DownloadStatus.PAUSED, null);
                return;
            }
            if (!ok) {
                finish(d, DownloadStatus.FAILED,
                        d.error != null ? d.error : "Transfer failed");
                return;
            }

            d.status = DownloadStatus.PUBLISHING;
            store.update(d);
            listener.onProgress(d, 0);

            // Read the finished file while it is still ours and local; publishing moves it.
            // This both produces the poster and catches transfers that completed but are not
            // actually playable, rather than putting a 0:00 file in the user's gallery.
            DownloadThumbnails.Result finished =
                    new DownloadThumbnails(context).inspect(temp, downloadId);
            if (!finished.playable()) {
                throw new IOException("Downloaded file is not a playable video");
            }
            // A sound track yields no frame, which is not a fault — the test above is a running
            // time, so it passes on its own merits and simply arrives without a poster.
            if (finished.posterPath != null) d.posterUrl = finished.posterPath;

            boolean audio = d.kind == MediaKind.AUDIO;
            String output = new MediaStorePublisher(context)
                    .publish(temp, d.fileName, d.mime, audio);
            d.outputUri = output;
            d.completedAt = System.currentTimeMillis();
            if (d.totalBytes <= 0) d.totalBytes = downloaded.get();
            d.downloadedBytes = d.totalBytes;
            store.deleteChunks(downloadId);
            finish(d, DownloadStatus.COMPLETED, null);

        } catch (Exception e) {
            Log.e(TAG, "Download " + downloadId + " failed", e);
            finish(d, cancelRequested ? DownloadStatus.CANCELLED : DownloadStatus.FAILED,
                    Errors.friendly(e));
        }
    }

    private void finish(DownloadEntity d, DownloadStatus status, @Nullable String error) {
        d.status = status;
        d.error = error;
        store.update(d);
        listener.onFinished(d);
    }

    private List<ChunkState> loadOrPlan(DownloadEntity d, File temp)
            throws IOException {
        List<ChunkEntity> existing = store.chunksFor(downloadId);

        // If the scratch file vanished (cache cleared) any saved progress is meaningless.
        if (!existing.isEmpty() && !temp.exists()) {
            store.deleteChunks(downloadId);
            existing = new ArrayList<>();
        }

        if (existing.isEmpty()) {
            store.insertChunks(plan(d));
            existing = store.chunksFor(downloadId);
        }

        if (d.totalBytes > 0) {
            try (RandomAccessFile raf = new RandomAccessFile(temp, "rw")) {
                if (raf.length() != d.totalBytes) raf.setLength(d.totalBytes);
            }
        } else if (!temp.exists() && !temp.createNewFile()) {
            throw new IOException("Cannot create " + temp);
        }

        List<ChunkState> states = new ArrayList<>(existing.size());
        long sum = 0;
        for (ChunkEntity c : existing) {
            states.add(new ChunkState(c));
            sum += c.downloadedBytes;
        }
        downloaded.set(sum);
        return states;
    }

    private List<ChunkEntity> plan(DownloadEntity d) {
        List<ChunkEntity> chunks = new ArrayList<>();
        long total = d.totalBytes;

        if (total <= 0 || !d.acceptsRanges || total < MIN_CHUNK_BYTES * 2) {
            chunks.add(new ChunkEntity(downloadId, 0, 0, total > 0 ? total - 1 : Long.MAX_VALUE));
            return chunks;
        }

        int connections = (int) Math.min(MAX_CONNECTIONS, total / MIN_CHUNK_BYTES);
        connections = Math.max(2, connections);
        long size = total / connections;
        for (int i = 0; i < connections; i++) {
            long start = i * size;
            long end = (i == connections - 1) ? total - 1 : (start + size - 1);
            chunks.add(new ChunkEntity(downloadId, i, start, end));
        }
        return chunks;
    }

    private boolean transfer(DownloadEntity d, File temp, List<ChunkState> chunks, boolean multi) {
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(chunks.size());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(chunks.size(), MAX_CONNECTIONS));

        for (ChunkState cs : chunks) {
            pool.execute(() -> {
                try {
                    runChunk(d, temp, cs, multi);
                } catch (Exception e) {
                    if (!pauseRequested && !cancelRequested) {
                        error.compareAndSet(null, String.valueOf(e.getMessage()));
                        cancelCalls();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
        pool.shutdown();

        reportUntilDone(d, chunks, latch);

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (error.get() != null) {
            d.error = error.get();
            return false;
        }
        if (pauseRequested || cancelRequested) return false;
        if (d.totalBytes > 0 && downloaded.get() < d.totalBytes) {
            d.error = "Incomplete transfer";
            return false;
        }
        return true;
    }

    /** Drives progress persistence and UI updates while the chunk workers run. */
    private void reportUntilDone(DownloadEntity d, List<ChunkState> chunks, CountDownLatch latch) {
        Thread reporter = new Thread(() -> {
            long lastBytes = downloaded.get();
            long lastAt = System.currentTimeMillis();
            while (latch.getCount() > 0) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(PERSIST_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long now = System.currentTimeMillis();
                long bytes = downloaded.get();
                long elapsed = Math.max(1, now - lastAt);
                long speed = (bytes - lastBytes) * 1000 / elapsed;
                lastBytes = bytes;
                lastAt = now;

                d.downloadedBytes = bytes;
                try {
                    persist(d, chunks);
                } catch (Exception ignored) {
                }
                listener.onProgress(d, Math.max(0, speed));
            }
        }, "dl-report-" + downloadId);
        reporter.setDaemon(true);
        reporter.start();
    }

    private void persist(DownloadEntity d, List<ChunkState> chunks) {
        for (ChunkState cs : chunks) {
            long v = cs.done.get();
            if (cs.entity.downloadedBytes != v) {
                cs.entity.downloadedBytes = v;
                store.updateChunkProgress(cs.entity.id, v);
            }
        }
        d.downloadedBytes = downloaded.get();
        store.updateProgress(downloadId, d.downloadedBytes);
    }

    private void runChunk(DownloadEntity d, File temp, ChunkState cs, boolean multi) throws IOException {
        if (cs.complete()) return;
        try {
            transferChunk(d, temp, cs, multi, d.headers());
        } catch (DeniedException denied) {
            // The CDN rejected our headers. Instagram and Facebook signed URLs commonly refuse
            // a session cookie or a deep Referer, so try again with the minimum that works.
            Log.i(TAG, "HTTP " + denied.code + " on chunk " + cs.entity.chunkIndex
                    + ", retrying with relaxed headers");
            Map<String, String> relaxed = Http.relaxed(d.headers());
            try {
                transferChunk(d, temp, cs, multi, relaxed);
                rememberWorkingHeaders(d, relaxed);
            } catch (DeniedException stillDenied) {
                throw new IOException(Errors.forStatus(stillDenied.code));
            }
        }
    }

    /** Persists the header set that worked so a resume or retry does not rediscover it. */
    private void rememberWorkingHeaders(DownloadEntity d, Map<String, String> headers) {
        try {
            d.setHeaders(headers);
            store.update(d);
        } catch (Exception ignored) {
        }
    }

    private static class DeniedException extends IOException {
        final int code;

        DeniedException(int code) {
            super("HTTP " + code);
            this.code = code;
        }
    }

    private void transferChunk(DownloadEntity d, File temp, ChunkState cs, boolean multi,
                               Map<String, String> headers) throws IOException {
        if (cs.complete()) return;

        boolean ranged = multi || cs.done.get() > 0;

        Request.Builder rb = new Request.Builder().url(d.sourceUrl).get();
        Http.withCaptured(rb, headers);
        if (ranged) {
            String range = "bytes=" + cs.cursor() + "-"
                    + (cs.entity.endByte == Long.MAX_VALUE ? "" : cs.entity.endByte);
            rb.header("Range", range);
        }
        // Identity encoding keeps byte offsets meaningful.
        rb.header("Accept-Encoding", "identity");

        Call call = Http.client().newCall(rb.build());
        inFlight.add(call);
        try (Response resp = call.execute()) {
            int code = resp.code();
            if (ranged && code == 200) {
                if (multi) throw new IOException("Server ignored Range header");
                // Single stream and the server restarted at zero: rewind our accounting.
                downloaded.addAndGet(-cs.done.get());
                cs.done.set(0);
            } else if (Http.deniedByHeaders(code)) {
                throw new DeniedException(code);
            } else if (code != 200 && code != 206) {
                throw new IOException(Errors.forStatus(code));
            }

            ResponseBody body = resp.body();
            if (body == null) throw new IOException("Empty response body");

            try (InputStream in = body.byteStream();
                 RandomAccessFile raf = new RandomAccessFile(temp, "rw")) {
                raf.seek(cs.cursor());
                byte[] buf = new byte[BUFFER];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (pauseRequested || cancelRequested) return;

                    int writable = n;
                    if (cs.entity.endByte != Long.MAX_VALUE) {
                        long remaining = cs.entity.size() - cs.done.get();
                        if (remaining <= 0) break;
                        writable = (int) Math.min(n, remaining);
                    }
                    raf.write(buf, 0, writable);
                    cs.done.addAndGet(writable);
                    downloaded.addAndGet(writable);

                    if (cs.complete()) break;
                }
            }
        } finally {
            inFlight.remove(call);
        }
    }
}
