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
import com.ms.webview.data.SegmentEntity;
import com.ms.webview.detect.hls.HlsHttp;
import com.ms.webview.detect.hls.HlsParser;
import com.ms.webview.detect.hls.HlsPlaylist;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Downloads an HLS rendition: fetch the media playlist, pull every segment, decrypt AES-128
 * where required, concatenate in order, then rewrap as MP4.
 *
 * <p>Segments are checkpointed individually, so an interrupted run resumes at the first one
 * still missing rather than starting the stream over.
 */
public class HlsDownloader implements DownloadTask {

    private static final String TAG = "HlsDownloader";

    /**
     * Each worker holds a whole segment in memory so it can be decrypted as a unit, so this is
     * a memory ceiling as much as a concurrency one. Segments are typically 1-5 MB.
     */
    private static final int PARALLEL_SEGMENTS = 4;
    private static final int SEGMENT_ATTEMPTS = 3;
    private static final long PROGRESS_INTERVAL_MS = 700;

    private final Context context;
    private final DownloadStore store;
    private final long downloadId;
    private final Listener listener;

    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger segmentsDone = new AtomicInteger();
    private final ConcurrentHashMap<String, byte[]> keyCache = new ConcurrentHashMap<>();

    private volatile boolean pauseRequested;
    private volatile boolean cancelRequested;
    /**
     * Parsed once: deserialising the captured headers per segment would be wasteful. Volatile
     * because a worker may swap in the relaxed set for everyone once the CDN refuses the
     * original one.
     */
    private volatile Map<String, String> headers = java.util.Collections.emptyMap();

    public HlsDownloader(Context context, DownloadStore store, long downloadId, Listener listener) {
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
    }

    @Override
    public void cancel() {
        cancelRequested = true;
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

            List<SegmentEntity> segments = store.segmentsFor(downloadId);
            if (segments.isEmpty()) {
                store.insertSegments(buildSegments(d));
                segments = store.segmentsFor(downloadId);
            }
            if (segments.isEmpty()) throw new IOException("Playlist contained no segments");

            d.segmentTotal = segments.size();
            seedProgress(segments, workDir);
            d.segmentsDone = segmentsDone.get();
            store.update(d);

            fetchSegments(d, workDir, segments);

            if (cancelRequested) {
                deleteTree(workDir);
                store.deleteSegments(downloadId);
                finish(d, DownloadStatus.CANCELLED, null);
                return;
            }
            if (pauseRequested) {
                persist(d);
                finish(d, DownloadStatus.PAUSED, null);
                return;
            }

            d.status = DownloadStatus.PUBLISHING;
            d.segmentsDone = segmentsDone.get();
            d.downloadedBytes = bytes.get();
            d.totalBytes = bytes.get();
            d.totalEstimated = false;
            store.update(d);
            listener.onProgress(d, 0);

            File playable = assemble(d, workDir, segments);

            // Read the finished file while it is still ours and local; publishing moves it.
            // A remux that produced nothing usable is caught here rather than in the gallery.
            DownloadThumbnails.Result finished =
                    new DownloadThumbnails(context).inspect(playable, downloadId);
            if (!finished.playable()) {
                throw new IOException("Assembled file is not a playable video");
            }
            if (finished.posterPath != null) d.posterUrl = finished.posterPath;

            String output = new MediaStorePublisher(context)
                    .publish(playable, d.fileName, d.mime);

            d.outputUri = output;
            d.completedAt = System.currentTimeMillis();
            store.deleteSegments(downloadId);
            deleteTree(workDir);
            finish(d, DownloadStatus.COMPLETED, null);

        } catch (Exception e) {
            Log.e(TAG, "HLS download " + downloadId + " failed", e);
            if (cancelRequested) {
                deleteTree(workDir);
                finish(d, DownloadStatus.CANCELLED, null);
            } else if (pauseRequested) {
                finish(d, DownloadStatus.PAUSED, null);
            } else {
                finish(d, DownloadStatus.FAILED, Errors.friendly(e));
            }
        }
    }

    // ---------------------------------------------------------------- playlist

    private List<SegmentEntity> buildSegments(DownloadEntity d) throws IOException {
        HlsPlaylist playlist = HlsParser.parse(
                HlsHttp.fetchText(d.sourceUrl, headers), d.sourceUrl);

        // Defensive: if a master got queued instead of a rendition, take the best one.
        if (playlist.master && !playlist.renditions.isEmpty()) {
            HlsPlaylist.Rendition best = null;
            for (HlsPlaylist.Rendition r : playlist.renditions) {
                if (best == null || r.bandwidth > best.bandwidth) best = r;
            }
            d.sourceUrl = best.url;
            if (TextUtils.isEmpty(d.audioUrl)) d.audioUrl = best.audioUrl;
            store.update(d);
            playlist = HlsParser.parse(HlsHttp.fetchText(d.sourceUrl, headers), d.sourceUrl);
        }

        if (playlist.drmProtected) throw new IOException("Stream is DRM protected");
        if (playlist.live) throw new IOException("Live streams cannot be downloaded");
        if (!playlist.hasSegments()) throw new IOException("Playlist contained no segments");

        List<SegmentEntity> all = new ArrayList<>();
        appendTrack(all, playlist, SegmentEntity.TRACK_VIDEO);

        if (!TextUtils.isEmpty(d.audioUrl)) {
            HlsPlaylist audio = HlsParser.parse(
                    HlsHttp.fetchText(d.audioUrl, headers), d.audioUrl);
            if (audio.drmProtected) throw new IOException("Audio stream is DRM protected");
            appendTrack(all, audio, SegmentEntity.TRACK_AUDIO);
        }
        return all;
    }

    private void appendTrack(List<SegmentEntity> out, HlsPlaylist playlist, int track) {
        int seq = 0;
        if (!TextUtils.isEmpty(playlist.initSegmentUrl)) {
            // fMP4 playlists need the EXT-X-MAP init segment ahead of everything else.
            SegmentEntity init = new SegmentEntity(downloadId, track, seq++, playlist.initSegmentUrl);
            init.byteStart = playlist.initByteStart;
            init.byteLength = playlist.initByteLength;
            out.add(init);
        }
        for (HlsPlaylist.Segment s : playlist.segments) {
            SegmentEntity e = new SegmentEntity(downloadId, track, seq++, s.url);
            e.keyUri = s.keyUri;
            e.iv = s.ivHex;
            e.mediaSequence = s.mediaSequence;
            e.byteStart = s.byteStart;
            e.byteLength = s.byteLength;
            out.add(e);
        }
    }

    // ---------------------------------------------------------------- transfer

    /** Counts what a previous run already fetched, so resume does not redo it. */
    private void seedProgress(List<SegmentEntity> segments, File workDir) {
        long done = 0;
        int count = 0;
        for (SegmentEntity s : segments) {
            if (s.done && new File(workDir, s.fileName()).exists()) {
                done += s.bytes;
                count++;
            } else {
                s.done = false;
            }
        }
        bytes.set(done);
        segmentsDone.set(count);
    }

    private void fetchSegments(DownloadEntity d, File workDir, List<SegmentEntity> segments) {
        List<SegmentEntity> pending = new ArrayList<>();
        for (SegmentEntity s : segments) {
            if (!s.done) pending.add(s);
        }
        if (pending.isEmpty()) return;

        AtomicReference<String> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(pending.size());
        ExecutorService pool = Executors.newFixedThreadPool(PARALLEL_SEGMENTS);

        for (SegmentEntity s : pending) {
            pool.execute(() -> {
                try {
                    if (stopping() || error.get() != null) return;
                    downloadSegment(workDir, s);
                } catch (Exception e) {
                    if (!stopping()) error.compareAndSet(null, String.valueOf(e.getMessage()));
                } finally {
                    latch.countDown();
                }
            });
        }
        pool.shutdown();

        report(d, latch);

        try {
            latch.await();
            pool.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (error.get() != null && !stopping()) {
            throw new IllegalStateException(error.get());
        }
    }

    private void downloadSegment(File workDir, SegmentEntity s) throws Exception {
        File target = new File(workDir, s.fileName());
        IOException last = null;

        for (int attempt = 1; attempt <= SEGMENT_ATTEMPTS; attempt++) {
            if (stopping()) return;
            try {
                byte[] data;
                try {
                    data = HlsHttp.fetchBytes(s.url, headers, s.byteStart, s.byteLength);
                } catch (HlsHttp.StatusException status) {
                    if (!Http.deniedByHeaders(status.code)) throw status;
                    // Signed segment URLs often refuse the session cookie or a deep Referer.
                    // Switch the whole download over once the relaxed set is shown to work.
                    Map<String, String> relaxed = Http.relaxed(headers);
                    data = HlsHttp.fetchBytes(s.url, relaxed, s.byteStart, s.byteLength);
                    headers = relaxed;
                    Log.i(TAG, "Switched to relaxed headers after HTTP " + status.code);
                }
                if (!TextUtils.isEmpty(s.keyUri)) {
                    data = decrypt(data, key(s.keyUri), ivFor(s));
                }

                // Write to a scratch name first: a half-written segment must never look done.
                File tmp = new File(workDir, s.fileName() + ".tmp");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target);
                if (!tmp.renameTo(target)) throw new IOException("Cannot finalise " + target);

                s.done = true;
                s.bytes = data.length;
                store.markSegmentDone(s.id, data.length);
                bytes.addAndGet(data.length);
                segmentsDone.incrementAndGet();
                return;

            } catch (IOException e) {
                last = e;
                Log.w(TAG, "Segment " + s.seq + " attempt " + attempt + " failed: " + e.getMessage());
                if (attempt < SEGMENT_ATTEMPTS && !stopping()) {
                    try {
                        Thread.sleep(400L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
        if (last != null && !stopping()) throw last;
    }

    private byte[] key(String keyUri) throws IOException {
        byte[] cached = keyCache.get(keyUri);
        if (cached != null) return cached;
        byte[] key = HlsHttp.fetchBytes(keyUri, headers, -1, -1);
        if (key.length != 16) throw new IOException("Unexpected AES-128 key length " + key.length);
        keyCache.put(keyUri, key);
        return key;
    }

    /** Explicit IV when the playlist gives one, otherwise the media sequence number. */
    private static byte[] ivFor(SegmentEntity s) {
        if (!TextUtils.isEmpty(s.iv)) {
            String hex = s.iv.trim();
            if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
            byte[] iv = new byte[16];
            int chars = Math.min(32, hex.length());
            // Right-align: shorter values are zero-padded on the left.
            int ivIndex = 16 - (chars + 1) / 2;
            for (int i = 0; i < chars; i += 2) {
                iv[ivIndex++] = (byte) Integer.parseInt(hex.substring(i, Math.min(i + 2, chars)), 16);
            }
            return iv;
        }
        byte[] iv = new byte[16];
        long seq = s.mediaSequence;
        for (int i = 15; i >= 8; i--) {
            iv[i] = (byte) (seq & 0xFF);
            seq >>>= 8;
        }
        return iv;
    }

    private static byte[] decrypt(byte[] data, byte[] key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return cipher.doFinal(data);
    }

    // ---------------------------------------------------------------- assembly

    /**
     * Concatenates the segments and rewraps them as MP4. If the platform muxer cannot read the
     * stream we still hand back the raw concatenation, which nearly every player can open.
     */
    private File assemble(DownloadEntity d, File workDir, List<SegmentEntity> segments)
            throws IOException {
        File video = concatenate(workDir, segments, SegmentEntity.TRACK_VIDEO, "video.bin");
        File audio = null;
        if (!TextUtils.isEmpty(d.audioUrl)) {
            audio = concatenate(workDir, segments, SegmentEntity.TRACK_AUDIO, "audio.bin");
        }

        File output = new File(workDir, "output.mp4");
        try {
            Remuxer.remux(video, audio, output);
            //noinspection ResultOfMethodCallIgnored
            video.delete();
            if (audio != null) {
                //noinspection ResultOfMethodCallIgnored
                audio.delete();
            }
            d.mime = "video/mp4";
            return output;
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            output.delete();
            if (audio != null) {
                // Video and audio arrived separately and we cannot join them, so there is no
                // usable single file to hand over.
                throw new IOException("Could not mux audio and video: " + e.getMessage());
            }
            Log.w(TAG, "Remux failed, keeping the raw transport stream", e);
            d.mime = "video/mp2ts";
            d.fileName = swapExtension(d.fileName, "ts");
            return video;
        }
    }

    private File concatenate(File workDir, List<SegmentEntity> segments, int track, String name)
            throws IOException {
        File out = new File(workDir, name);
        byte[] buf = new byte[64 * 1024];
        try (OutputStream os = new FileOutputStream(out)) {
            for (SegmentEntity s : segments) {
                if (s.track != track) continue;
                File part = new File(workDir, s.fileName());
                if (!part.exists()) throw new IOException("Missing segment " + s.seq);
                try (InputStream in = new FileInputStream(part)) {
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                // Freeing each part as we go keeps peak disk use near the final file size.
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
        return out;
    }

    private static String swapExtension(String fileName, String extension) {
        if (TextUtils.isEmpty(fileName)) return "video." + extension;
        int dot = fileName.lastIndexOf('.');
        return (dot > 0 ? fileName.substring(0, dot) : fileName) + "." + extension;
    }

    // ---------------------------------------------------------------- progress

    private void report(DownloadEntity d, CountDownLatch latch) {
        Thread reporter = new Thread(() -> {
            long lastBytes = bytes.get();
            long lastAt = System.currentTimeMillis();
            while (latch.getCount() > 0) {
                try {
                    //noinspection BusyWait
                    Thread.sleep(PROGRESS_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                long now = System.currentTimeMillis();
                long current = bytes.get();
                long speed = (current - lastBytes) * 1000 / Math.max(1, now - lastAt);
                lastBytes = current;
                lastAt = now;

                persist(d);
                listener.onProgress(d, Math.max(0, speed));
            }
        }, "hls-report-" + downloadId);
        reporter.setDaemon(true);
        reporter.start();
    }

    private void persist(DownloadEntity d) {
        d.downloadedBytes = bytes.get();
        d.segmentsDone = segmentsDone.get();
        // Extrapolate the total from what the finished segments actually weighed. This converges
        // within a few segments and beats the bitrate estimate the sheet started with.
        if (d.segmentsDone > 0 && d.segmentTotal > 0) {
            d.totalBytes = d.downloadedBytes * d.segmentTotal / d.segmentsDone;
            d.totalEstimated = true;
        }
        try {
            store.update(d);
        } catch (Exception ignored) {
        }
    }

    private void finish(DownloadEntity d, DownloadStatus status, @Nullable String error) {
        d.status = status;
        d.error = error;
        store.update(d);
        listener.onFinished(d);
    }

    private static void deleteTree(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) deleteTree(f);
                //noinspection ResultOfMethodCallIgnored
                else f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
