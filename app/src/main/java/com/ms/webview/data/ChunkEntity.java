package com.ms.webview.data;

/**
 * One byte range of a multi-connection download. Persisting per-chunk progress is what lets a
 * download survive the process being killed mid-transfer.
 */
public class ChunkEntity {

    public long id;

    public long downloadId;
    public int chunkIndex;
    public long startByte;
    /** Inclusive. */
    public long endByte;
    public long downloadedBytes;

    public ChunkEntity() {
    }

    public ChunkEntity(long downloadId, int chunkIndex, long startByte, long endByte) {
        this.downloadId = downloadId;
        this.chunkIndex = chunkIndex;
        this.startByte = startByte;
        this.endByte = endByte;
    }

    public long size() {
        return endByte - startByte + 1;
    }

    public boolean complete() {
        return downloadedBytes >= size();
    }

    /** Absolute file offset to write the next byte at. */
    public long cursor() {
        return startByte + downloadedBytes;
    }
}
