package com.ms.webview.download;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live transfer rates, by download id.
 *
 * <p>Kept in memory rather than on the row. Speed changes every few hundred milliseconds and is
 * meaningless the moment the app is closed, so persisting it would mean a schema change and an
 * extra write per tick to store something with no value after a restart.
 */
public final class DownloadSpeeds {

    private static final Map<Long, Long> BYTES_PER_SECOND = new ConcurrentHashMap<>();

    private DownloadSpeeds() {
    }

    public static void record(long downloadId, long bytesPerSecond) {
        if (bytesPerSecond > 0) BYTES_PER_SECOND.put(downloadId, bytesPerSecond);
        else BYTES_PER_SECOND.remove(downloadId);
    }

    /** Zero when the download is not running, which the caller shows as no rate at all. */
    public static long of(long downloadId) {
        Long value = BYTES_PER_SECOND.get(downloadId);
        return value == null ? 0 : value;
    }

    public static void clear(long downloadId) {
        BYTES_PER_SECOND.remove(downloadId);
    }
}
