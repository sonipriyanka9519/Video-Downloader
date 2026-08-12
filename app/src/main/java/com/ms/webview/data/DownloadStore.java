package com.ms.webview.data;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Bookkeeping for transfers that have not finished yet.
 *
 * <p>This is all that is left of the old database, and it is deliberately small. A finished
 * video is recorded by the device itself — see {@link MediaLibrary} — so nothing here needs to
 * outlive the transfer it describes. What does need to survive is the state that makes a
 * download resumable after the process is killed: byte ranges, segment lists, and the request
 * headers the CDN insists on.
 *
 * <p>A JSON file rather than SQLite, because that is the whole of the requirement: a handful of
 * records, read once at startup, written back as they change. Everything is held in memory and
 * the file is rewritten on a short debounce, so a segment finishing every few hundred
 * milliseconds does not mean a disk write every few hundred milliseconds.
 */
public class DownloadStore {

    private static final String TAG = "DownloadStore";
    private static final String FILE = "downloads.json";
    private static final long WRITE_DELAY_MS = 400L;

    /** One transfer and its checkpoints, kept together so a delete cannot leave orphans. */
    private static class Record {
        DownloadEntity download;
        List<ChunkEntity> chunks = new ArrayList<>();
        List<SegmentEntity> segments = new ArrayList<>();
    }

    private static class Snapshot {
        long nextId = 1;
        long nextChildId = 1;
        List<Record> records = new ArrayList<>();
    }

    private static volatile DownloadStore instance;

    private final File file;
    private final Gson gson = new Gson();
    private final Object lock = new Object();

    /** Insertion-ordered so the list the UI sees does not reshuffle on every write. */
    private final Map<Long, Record> records = new LinkedHashMap<>();
    private long nextId = 1;
    private long nextChildId = 1;

    private final MutableLiveData<List<DownloadEntity>> live = new MutableLiveData<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor();
    @Nullable
    private ScheduledFuture<?> pendingWrite;

    public static DownloadStore get(Context context) {
        if (instance == null) {
            synchronized (DownloadStore.class) {
                if (instance == null) instance = new DownloadStore(context);
            }
        }
        return instance;
    }

    private DownloadStore(Context context) {
        this.file = new File(context.getApplicationContext().getFilesDir(), FILE);
        load();
        publish();
        dropOldDatabase(context);
    }

    /**
     * Clears out the SQLite database this replaced. An app that has been upgraded rather than
     * reinstalled would otherwise carry it around forever, and nothing reads it now.
     */
    private static void dropOldDatabase(Context context) {
        try {
            context.getApplicationContext().deleteDatabase("webview.db");
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ downloads

    public long insert(DownloadEntity entity) {
        synchronized (lock) {
            entity.id = nextId++;
            Record record = new Record();
            record.download = entity;
            records.put(entity.id, record);
            save();
        }
        publish();
        return entity.id;
    }

    /**
     * Writes the row back. Completed rows stay only until the next start: the gallery is the
     * record from that point on, and keeping both would list the same video twice.
     */
    public void update(DownloadEntity entity) {
        if (entity == null) return;
        synchronized (lock) {
            Record record = records.get(entity.id);
            if (record == null) return;
            record.download = entity;
            if (entity.status == DownloadStatus.COMPLETED) {
                record.chunks.clear();
                record.segments.clear();
            }
            save();
        }
        publish();
    }

    @Nullable
    public DownloadEntity byId(long id) {
        synchronized (lock) {
            Record record = records.get(id);
            return record == null ? null : record.download;
        }
    }

    public void updateProgress(long id, long bytes) {
        synchronized (lock) {
            Record record = records.get(id);
            if (record == null) return;
            record.download.downloadedBytes = bytes;
            save();
        }
        publish();
    }

    public void updateStatus(long id, DownloadStatus status) {
        synchronized (lock) {
            Record record = records.get(id);
            if (record == null) return;
            record.download.status = status;
            save();
        }
        publish();
    }

    public void delete(long id) {
        synchronized (lock) {
            if (records.remove(id) == null) return;
            save();
        }
        publish();
    }

    @Nullable
    public DownloadEntity findBySource(String url) {
        if (TextUtils.isEmpty(url)) return null;
        synchronized (lock) {
            for (Record record : records.values()) {
                DownloadEntity d = record.download;
                if (url.equals(d.sourceUrl) && d.status != DownloadStatus.CANCELLED) return d;
            }
        }
        return null;
    }

    /** Newest first, and never the completed ones — those belong to the library now. */
    public List<DownloadEntity> all() {
        List<DownloadEntity> list = new ArrayList<>();
        synchronized (lock) {
            for (Record record : records.values()) {
                if (record.download.status != DownloadStatus.COMPLETED) list.add(record.download);
            }
        }
        Collections.sort(list, new Comparator<DownloadEntity>() {
            @Override
            public int compare(DownloadEntity a, DownloadEntity b) {
                return Long.compare(b.createdAt, a.createdAt);
            }
        });
        return list;
    }

    public LiveData<List<DownloadEntity>> live() {
        return live;
    }

    /**
     * Called once at startup. Anything still marked running belongs to a process that no longer
     * exists, and anything already completed has been handed to the gallery.
     */
    public void reconcileOnStartup() {
        synchronized (lock) {
            List<Long> finished = new ArrayList<>();
            for (Map.Entry<Long, Record> entry : records.entrySet()) {
                DownloadEntity d = entry.getValue().download;
                // Completed rows are the gallery's now; cancelled ones left nothing behind.
                // Failed ones stay, because retrying them is still worth offering.
                if (d.status == DownloadStatus.COMPLETED
                        || d.status == DownloadStatus.CANCELLED) {
                    finished.add(entry.getKey());
                } else if (d.status == DownloadStatus.RUNNING
                        || d.status == DownloadStatus.PUBLISHING) {
                    d.status = DownloadStatus.PAUSED;
                }
            }
            for (Long id : finished) records.remove(id);
            save();
        }
        publish();
    }

    // --------------------------------------------------------------------- chunks

    public List<ChunkEntity> chunksFor(long downloadId) {
        synchronized (lock) {
            Record record = records.get(downloadId);
            return record == null ? new ArrayList<>() : new ArrayList<>(record.chunks);
        }
    }

    public void insertChunks(List<ChunkEntity> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        synchronized (lock) {
            for (ChunkEntity chunk : chunks) {
                Record record = records.get(chunk.downloadId);
                if (record == null) continue;
                chunk.id = nextChildId++;
                record.chunks.add(chunk);
            }
            save();
        }
    }

    public void updateChunkProgress(long chunkId, long bytes) {
        synchronized (lock) {
            for (Record record : records.values()) {
                for (ChunkEntity chunk : record.chunks) {
                    if (chunk.id == chunkId) {
                        chunk.downloadedBytes = bytes;
                        save();
                        return;
                    }
                }
            }
        }
    }

    public void deleteChunks(long downloadId) {
        synchronized (lock) {
            Record record = records.get(downloadId);
            if (record == null || record.chunks.isEmpty()) return;
            record.chunks.clear();
            save();
        }
    }

    // ------------------------------------------------------------------- segments

    public List<SegmentEntity> segmentsFor(long downloadId) {
        synchronized (lock) {
            Record record = records.get(downloadId);
            return record == null ? new ArrayList<>() : new ArrayList<>(record.segments);
        }
    }

    public void insertSegments(List<SegmentEntity> segments) {
        if (segments == null || segments.isEmpty()) return;
        synchronized (lock) {
            for (SegmentEntity segment : segments) {
                Record record = records.get(segment.downloadId);
                if (record == null) continue;
                segment.id = nextChildId++;
                record.segments.add(segment);
            }
            save();
        }
    }

    public int segmentCount(long downloadId) {
        synchronized (lock) {
            Record record = records.get(downloadId);
            return record == null ? 0 : record.segments.size();
        }
    }

    public void markSegmentDone(long segmentId, long bytes) {
        synchronized (lock) {
            for (Record record : records.values()) {
                for (SegmentEntity segment : record.segments) {
                    if (segment.id == segmentId) {
                        segment.done = true;
                        segment.bytes = bytes;
                        save();
                        return;
                    }
                }
            }
        }
    }

    public void deleteSegments(long downloadId) {
        synchronized (lock) {
            Record record = records.get(downloadId);
            if (record == null || record.segments.isEmpty()) return;
            record.segments.clear();
            save();
        }
    }

    // ---------------------------------------------------------------- persistence

    private void publish() {
        live.postValue(all());
    }

    /** Marks the state dirty; the actual write is coalesced. Must be called under the lock. */
    private void save() {
        if (pendingWrite != null) pendingWrite.cancel(false);
        pendingWrite = writer.schedule(this::writeNow, WRITE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private void writeNow() {
        Snapshot snapshot = new Snapshot();
        synchronized (lock) {
            snapshot.nextId = nextId;
            snapshot.nextChildId = nextChildId;
            snapshot.records = new ArrayList<>(records.values());
            // Serialised inside the lock: the records are mutated in place by the download
            // threads, and Gson walking a list mid-mutation is how you get a corrupt file.
            try {
                writeAtomically(gson.toJson(snapshot));
            } catch (Exception e) {
                Log.w(TAG, "Could not write download state: " + e.getMessage());
            }
        }
    }

    /**
     * Through a temporary file, so a process killed mid-write leaves the previous state intact
     * rather than a half-written one that would drop every resumable download.
     */
    private void writeAtomically(String json) throws Exception {
        File temp = new File(file.getParentFile(), FILE + ".tmp");
        try (Writer out = new OutputStreamWriter(
                new FileOutputStream(temp), StandardCharsets.UTF_8)) {
            out.write(json);
        }
        if (!temp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temp.renameTo(file)) throw new Exception("rename failed");
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (Reader in = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            Snapshot snapshot = gson.fromJson(in, new TypeToken<Snapshot>() {
            }.getType());
            if (snapshot == null) return;

            nextId = Math.max(1, snapshot.nextId);
            nextChildId = Math.max(1, snapshot.nextChildId);
            if (snapshot.records == null) return;

            for (Record record : snapshot.records) {
                if (record == null || record.download == null) continue;
                if (record.chunks == null) record.chunks = new ArrayList<>();
                if (record.segments == null) record.segments = new ArrayList<>();
                records.put(record.download.id, record);
                nextId = Math.max(nextId, record.download.id + 1);
            }
        } catch (Exception e) {
            // A file we cannot read is a file we start again from. The videos themselves are on
            // the device either way; only unfinished transfers are lost.
            Log.w(TAG, "Discarding unreadable download state: " + e.getMessage());
            records.clear();
        }
    }
}
