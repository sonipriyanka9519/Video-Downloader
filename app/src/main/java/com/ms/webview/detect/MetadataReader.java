package com.ms.webview.detect;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Http;
import com.ms.webview.detect.hls.HlsHttp;
import com.ms.webview.detect.hls.HlsParser;
import com.ms.webview.detect.hls.HlsPlaylist;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Reads a remote video's real properties by opening the stream itself.
 *
 * <p>A HEAD request tells us the size and the type and nothing else, which is why a plain MP4
 * had no resolution to show and no thumbnail. {@link MediaMetadataRetriever} accepts a URL with
 * headers, and one session yields the dimensions, the duration and a decoded frame — so the
 * quality label and the preview both come out of a single connection.
 */
public class MetadataReader {

    private static final String TAG = "MetadataReader";
    private static final int THUMB_MAX_WIDTH = 480;
    private static final int THUMB_QUALITY = 82;
    /** A second in, so we skip the black or logo frame most videos open on. */
    private static final long FRAME_AT_US = 1_000_000L;
    /**
     * Enough of a streaming-ordered MP4 to hold its moov atom and several seconds of video.
     * This is real bandwidth spent while merely browsing, so it is kept modest and the number
     * of these reads is capped by the caller.
     */
    private static final long HEAD_BYTES = 1_000_000L;
    /** Segments to stitch together from the front of an HLS stream. */
    private static final int MAX_HEAD_SEGMENTS = 3;

    public static class Result {
        public int width;
        public int height;
        public long durationMs;
        public long bitrate;
        /** Absolute path of the extracted frame, or null when none was captured. */
        @Nullable
        public String thumbnailPath;
        /**
         * True when only the front of the stream was fetched, so {@link #durationMs} measures
         * the clip we built rather than the video. The manifest is the authority on that.
         */
        public boolean partialDuration;

        /**
         * Whether this is a real, playable video rather than a fragment of one. A media
         * segment or an audio-only stream opens without error but has no dimensions and no
         * duration, so this is the test that keeps them out of the sheet.
         */
        public boolean playableVideo() {
            return width > 0 && height > 0 && durationMs > 0;
        }
    }

    private final Context context;

    public MetadataReader(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * @param captureFrame  whether to also decode a poster frame
     * @param allowDownload whether to fall back to fetching the head of the file ourselves when
     *                      the retriever cannot open the URL over the network
     */
    @Nullable
    public Result read(String url, @Nullable Map<String, String> headers, boolean captureFrame,
                       boolean allowDownload) {
        // Fetch the head of the file ourselves and decode that, in preference to handing the URL
        // to the retriever.
        //
        // The retriever has its own native HTTP stack, markedly less capable than OkHttp, and
        // when it cannot handle a CDN it does not fail fast — it blocks until the caller's
        // timeout expires. Trying it first therefore consumed the entire budget and the local
        // fallback never ran, which is why whole sites reported "found, but none could be
        // opened". Doing it this way round the transfer uses the header set the probe already
        // proved, and the decode is purely local.
        //
        // A playlist is excluded: its body is a manifest of relative URLs, useless on its own.
        if (allowDownload) {
            Result local = isPlaylist(url)
                    ? readPlaylistHead(url, headers, captureFrame)
                    : readLocalCopy(url, headers, captureFrame);
            if (local != null && local.playableVideo()) {
                Log.i(MediaRegistry.DIAG, "decoded locally: " + url);
                return local;
            }
            Log.i(MediaRegistry.DIAG, "local decode failed, trying retriever: " + url);
        }
        return readRemote(url, headers, captureFrame);
    }

    private static boolean isPlaylist(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        return lower.contains(".m3u8");
    }

    /**
     * Builds a short playable clip from the front of an HLS stream and decodes that.
     *
     * <p>A playlist body is a manifest of relative URLs, so there is nothing in it to decode —
     * which left every HLS video unable to produce a preview, and therefore unable to appear at
     * all. Fetching the init segment and the first couple of media segments yields a few
     * seconds of real video, enough for the dimensions and a frame.
     */
    @Nullable
    private Result readPlaylistHead(String url, @Nullable Map<String, String> headers,
                                    boolean captureFrame) {
        File temp = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            temp = downloadPlaylistHead(url, headers);
            if (temp == null) return null;

            retriever.setDataSource(temp.getAbsolutePath());
            Result result = readTracks(retriever);
            // Only a few seconds were fetched, so this duration describes the clip, not the
            // video. The playlist is the authority on that.
            result.partialDuration = true;
            if (captureFrame && result.playableVideo()) {
                result.thumbnailPath = extractFrame(retriever, url);
            }
            return result;

        } catch (Exception | OutOfMemoryError e) {
            Log.d(TAG, "Playlist head decode failed for " + url + ": " + e.getMessage());
            return null;
        } finally {
            release(retriever);
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    @Nullable
    private File downloadPlaylistHead(String url, @Nullable Map<String, String> headers)
            throws IOException {
        HlsPlaylist playlist = HlsParser.parse(HlsHttp.fetchText(url, headers), url);

        if (playlist.master && !playlist.renditions.isEmpty()) {
            // The smallest rendition is the cheapest way to a representative frame.
            HlsPlaylist.Rendition smallest = null;
            for (HlsPlaylist.Rendition r : playlist.renditions) {
                if (smallest == null || r.bandwidth < smallest.bandwidth) smallest = r;
            }
            playlist = HlsParser.parse(HlsHttp.fetchText(smallest.url, headers), smallest.url);
        }
        if (!playlist.hasSegments() || playlist.drmProtected) return null;
        // Raw encrypted segments will not decode, and unwrapping them for a thumbnail is not
        // worth it — the download path handles decryption when the user actually asks for it.
        if (playlist.segments.get(0).keyUri != null) return null;

        File temp = scratchFile(url, "ts");
        long written = 0;
        try (OutputStream out = new FileOutputStream(temp)) {
            if (playlist.initSegmentUrl != null) {
                byte[] init = HlsHttp.fetchBytes(playlist.initSegmentUrl, headers,
                        playlist.initByteStart, playlist.initByteLength);
                out.write(init);
                written += init.length;
            }
            for (int i = 0; i < playlist.segments.size() && i < MAX_HEAD_SEGMENTS
                    && written < HEAD_BYTES; i++) {
                HlsPlaylist.Segment segment = playlist.segments.get(i);
                byte[] data = HlsHttp.fetchBytes(segment.url, headers,
                        segment.byteStart, segment.byteLength);
                out.write(data);
                written += data.length;
            }
        }
        if (written > 0) return temp;
        //noinspection ResultOfMethodCallIgnored
        temp.delete();
        return null;
    }

    /** Pulls the track properties out of an already-opened retriever. */
    private static Result readTracks(MediaMetadataRetriever retriever) {
        Result result = new Result();
        result.width = intOf(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        result.height = intOf(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        result.durationMs = longOf(retriever, MediaMetadataRetriever.METADATA_KEY_DURATION);
        result.bitrate = longOf(retriever, MediaMetadataRetriever.METADATA_KEY_BITRATE);

        // A rotated recording reports its stored dimensions, not its displayed ones.
        int rotation = intOf(retriever, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        if (rotation == 90 || rotation == 270) {
            int swap = result.width;
            result.width = result.height;
            result.height = swap;
        }
        return result;
    }

    @Nullable
    private Result readRemote(String url, @Nullable Map<String, String> headers,
                              boolean captureFrame) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(url, headers == null ? Collections.emptyMap() : headers);

            Result result = readTracks(retriever);
            // No point decoding a frame from something that is not a video.
            if (captureFrame && result.playableVideo()) {
                result.thumbnailPath = extractFrame(retriever, url);
            }
            return result;

        } catch (Exception | OutOfMemoryError e) {
            // Unreachable, unsupported codec, or a signed URL that has already expired.
            Log.d(TAG, "Cannot read " + url + ": " + e.getMessage());
            return null;
        } finally {
            release(retriever);
        }
    }

    /**
     * Downloads the first slice of the file and reads that. An MP4 written for streaming keeps
     * its moov atom at the front, so a megabyte is enough for the dimensions, the duration and
     * a frame. One that keeps it at the end will not decode, and is reported as unreadable.
     */
    @Nullable
    private Result readLocalCopy(String url, @Nullable Map<String, String> headers,
                                 boolean captureFrame) {
        File temp = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            temp = download(url, headers);
            if (temp == null) return null;

            retriever.setDataSource(temp.getAbsolutePath());

            Result result = readTracks(retriever);
            if (captureFrame && result.playableVideo()) {
                result.thumbnailPath = extractFrame(retriever, url);
            }
            return result;

        } catch (Exception | OutOfMemoryError e) {
            Log.d(TAG, "Local decode failed for " + url + ": " + e.getMessage());
            return null;
        } finally {
            release(retriever);
            if (temp != null) {
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        }
    }

    private File scratchFile(String url, String extension) {
        File dir = new File(context.getCacheDir(), "probe");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // Two reader threads run at once, so keep the scratch names distinct.
        return new File(dir, "p_" + Math.abs(url.hashCode())
                + "_" + Thread.currentThread().getId() + "." + extension);
    }

    @Nullable
    private File download(String url, @Nullable Map<String, String> headers) throws IOException {
        Request.Builder b = new Request.Builder().url(url).get()
                .header("Range", "bytes=0-" + (HEAD_BYTES - 1))
                .header("Accept-Encoding", "identity");
        Http.withCaptured(b, headers);

        try (Response response = Http.client().newCall(b.build()).execute()) {
            if (response.code() != 200 && response.code() != 206) {
                Log.i(MediaRegistry.DIAG, "head fetch HTTP " + response.code() + " for " + url);
                return null;
            }
            ResponseBody body = response.body();
            if (body == null) return null;

            File temp = scratchFile(url, "mp4");
            long written = 0;
            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) > 0 && written < HEAD_BYTES) {
                    out.write(buffer, 0, read);
                    written += read;
                }
            }
            return written > 0 ? temp : null;
        }
    }

    @Nullable
    private String extractFrame(MediaMetadataRetriever retriever, String url) {
        Bitmap frame = null;
        Bitmap scaled = null;
        try {
            frame = firstDecodableFrame(retriever);
            if (frame == null) return null;

            scaled = scale(frame);
            File target = new File(thumbnailDir(), fileNameFor(url));
            try (OutputStream out = new FileOutputStream(target)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, out);
            }
            return target.getAbsolutePath();

        } catch (Exception | OutOfMemoryError e) {
            Log.d(TAG, "Frame capture failed: " + e.getMessage());
            return null;
        } finally {
            if (scaled != null && scaled != frame) scaled.recycle();
            if (frame != null) frame.recycle();
        }
    }

    /**
     * A second in gives a more representative frame than the black or logo frame most videos
     * open on — but when only the head of the file is present there may be no sync frame there
     * yet, so fall back towards the start and finally to whatever decodes at all.
     */
    @Nullable
    private static Bitmap firstDecodableFrame(MediaMetadataRetriever retriever) {
        Bitmap frame = tryFrame(retriever, FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        if (frame == null) {
            frame = tryFrame(retriever, 0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        }
        if (frame == null) {
            frame = tryFrame(retriever, FRAME_AT_US, MediaMetadataRetriever.OPTION_CLOSEST);
        }
        if (frame == null) {
            try {
                frame = retriever.getFrameAtTime();
            } catch (Exception | OutOfMemoryError ignored) {
            }
        }
        return frame;
    }

    @Nullable
    private static Bitmap tryFrame(MediaMetadataRetriever retriever, long timeUs, int option) {
        try {
            return retriever.getFrameAtTime(timeUs, option);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static Bitmap scale(Bitmap source) {
        if (source.getWidth() <= THUMB_MAX_WIDTH) return source;
        int height = Math.max(1,
                Math.round(THUMB_MAX_WIDTH * (float) source.getHeight() / source.getWidth()));
        return Bitmap.createScaledBitmap(source, THUMB_MAX_WIDTH, height, true);
    }

    private File thumbnailDir() {
        File dir = new File(context.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static String fileNameFor(String url) {
        return String.format(Locale.US, "t_%08x_%d.jpg", url.hashCode(), url.length());
    }

    private static int intOf(MediaMetadataRetriever retriever, int key) {
        return (int) longOf(retriever, key);
    }

    private static long longOf(MediaMetadataRetriever retriever, int key) {
        try {
            String value = retriever.extractMetadata(key);
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static void release(MediaMetadataRetriever retriever) {
        try {
            retriever.release();
        } catch (Exception ignored) {
            // release() only started declaring IOException on API 29.
        }
    }
}
