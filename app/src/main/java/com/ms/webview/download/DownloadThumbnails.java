package com.ms.webview.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Posters for the downloads list.
 *
 * <p>These live in {@code filesDir}, not the cache: a detection-time frame sits in the cache and
 * can be evicted at any moment, which would leave a finished download with a blank tile weeks
 * later. Once a download completes the poster is regenerated from the downloaded file itself,
 * which is both authoritative and free — the decode is local and the bytes are already here.
 */
public class DownloadThumbnails {

    private static final String TAG = "DownloadThumbnails";
    private static final int MAX_WIDTH = 480;
    private static final int QUALITY = 82;
    private static final long FRAME_AT_US = 1_000_000L;

    private final Context context;

    public DownloadThumbnails(Context context) {
        this.context = context.getApplicationContext();
    }

    /** What a finished file turned out to contain. */
    public static class Result {
        @Nullable
        public String posterPath;
        public long durationMs;

        /**
         * A file that reports no duration is not a video: a stray fragment, a truncated
         * transfer, or a remux that produced nothing. Those are what land in the gallery
         * playing 0:00.
         */
        public boolean playable() {
            return durationMs > 0;
        }
    }

    /** Reads a finished download: its duration, and a frame to use as the poster. */
    public Result inspect(File video, long downloadId) {
        Result result = new Result();
        if (video == null || !video.exists() || video.length() == 0) return result;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        Bitmap scaled = null;
        try {
            retriever.setDataSource(video.getAbsolutePath());
            result.durationMs = longOf(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);

            frame = retriever.getFrameAtTime(FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            }
            if (frame == null) return result;

            scaled = scale(frame);
            File target = fileFor(downloadId);
            try (OutputStream out = new FileOutputStream(target)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out);
            }
            result.posterPath = target.getAbsolutePath();
            return result;

        } catch (Exception | OutOfMemoryError e) {
            Log.d(TAG, "Could not read finished file: " + e.getMessage());
            return result;
        } finally {
            if (scaled != null && scaled != frame) scaled.recycle();
            if (frame != null) frame.recycle();
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Copies a detection-time frame out of the cache so the row has a poster while the download
     * is still running. Anything that is not a local path — a platform poster URL — is returned
     * unchanged for the image loader to fetch.
     */
    @Nullable
    public String persist(@Nullable String source, long downloadId) {
        if (TextUtils.isEmpty(source) || !source.startsWith("/")) return source;

        File from = new File(source);
        if (!from.exists()) return null;

        File target = fileFor(downloadId);
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            return target.getAbsolutePath();
        } catch (Exception e) {
            Log.d(TAG, "Could not persist poster: " + e.getMessage());
            return null;
        }
    }

    public void delete(long downloadId) {
        //noinspection ResultOfMethodCallIgnored
        fileFor(downloadId).delete();
    }

    private File fileFor(long downloadId) {
        File dir = new File(context.getFilesDir(), "posters");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return new File(dir, downloadId + ".jpg");
    }

    private static long longOf(MediaMetadataRetriever retriever, int key) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Bitmap scale(Bitmap source) {
        if (source.getWidth() <= MAX_WIDTH) return source;
        int height = Math.max(1,
                Math.round(MAX_WIDTH * (float) source.getHeight() / source.getWidth()));
        return Bitmap.createScaledBitmap(source, MAX_WIDTH, height, true);
    }
}
