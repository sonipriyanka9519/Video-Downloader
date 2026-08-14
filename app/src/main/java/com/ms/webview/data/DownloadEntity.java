package com.ms.webview.data;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.ms.webview.detect.MediaKind;

import java.util.HashMap;
import java.util.Map;

/**
 * One download, in flight or finished.
 *
 * <p>Two very different things wear this shape. A transfer in progress is read back from
 * {@link DownloadStore}, which owns it only until the file lands in the gallery. A finished
 * video is built by {@link MediaLibrary} from the device's own media database, so it exists
 * whether or not this app has any record of it — which is what makes the list survive a cleared
 * cache or a reinstall.
 */
public class DownloadEntity {

    /** Positive for a transfer this app is running, negative for a video read off the device. */
    public long id;

    /** True when this row was built from MediaStore rather than from a transfer we started. */
    public boolean fromLibrary;

    public String title;
    public String pageUrl;
    public String sourceUrl;
    public String mime;
    public String quality;
    public String posterUrl;

    /** Which engine handles this: a single file, or an HLS segment list. */
    @NonNull
    public MediaKind kind = MediaKind.PROGRESSIVE;
    /** HLS only: separate audio playlist to be muxed in alongside a video-only rendition. */
    public String audioUrl;
    public int segmentTotal;
    public int segmentsDone;

    /**
     * How long the video runs, where that is known.
     *
     * <p>Zero for a transfer still in flight: a duration can only be read out of a finished file,
     * and guessing one from a partial download would put a number on the screen that changes.
     */
    public long durationMs;

    public long totalBytes;
    public long downloadedBytes;
    /** True while totalBytes is extrapolated rather than measured, as it is early in an HLS run. */
    public boolean totalEstimated;

    /**
     * Progressive downloads keep a single .part file here; HLS downloads keep a working
     * directory of segments at this path instead.
     */
    public String tempPath;
    /** MediaStore uri or public path once published. */
    public String outputUri;
    public String fileName;

    @NonNull
    public DownloadStatus status = DownloadStatus.QUEUED;

    public boolean acceptsRanges;
    public String error;
    public long createdAt;
    public long completedAt;

    /**
     * The request headers captured when the WebView fetched this URL, serialised. Not optional —
     * a resumed or retried download without them gets a 403 from most CDNs.
     */
    public String headersJson;

    public Map<String, String> headers() {
        if (headersJson == null || headersJson.isEmpty()) return new HashMap<>();
        try {
            Map<String, String> m = new Gson().fromJson(
                    headersJson, new TypeToken<Map<String, String>>() {
                    }.getType());
            return m == null ? new HashMap<>() : m;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    public void setHeaders(Map<String, String> headers) {
        headersJson = headers == null ? "{}" : new Gson().toJson(headers);
    }

    public int percent() {
        // An HLS run knows its segment count exactly long before it knows its byte count, so
        // segments are the more honest progress signal there.
        if (kind == MediaKind.HLS && segmentTotal > 0) {
            return (int) Math.min(100, segmentsDone * 100L / segmentTotal);
        }
        if (totalBytes <= 0) return 0;
        return (int) Math.min(100, downloadedBytes * 100 / totalBytes);
    }
}
