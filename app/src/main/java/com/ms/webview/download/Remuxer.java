package com.ms.webview.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewraps downloaded HLS media into a normal MP4 without re-encoding.
 *
 * <p>{@link MediaExtractor} reads MPEG-2 TS and fragmented MP4 natively and {@link MediaMuxer}
 * writes MP4, so a straight sample copy does the job. That is deliberately not ffmpeg: ffmpeg-kit
 * was retired in early 2025 and would add tens of megabytes per ABI to do what the platform
 * already does.
 */
public final class Remuxer {

    private static final String TAG = "Remuxer";
    private static final int DEFAULT_BUFFER = 1024 * 1024;
    private static final int MAX_BUFFER = 8 * 1024 * 1024;

    /** How many samples to look through for the earliest timestamp. See earliestSampleTime. */
    private static final int REORDER_SCAN = 240;

    private Remuxer() {
    }

    /**
     * @param video the concatenated video stream, or the muxed stream when {@code audio} is null
     * @param audio a separately downloaded audio rendition, or null when audio is already inside
     */
    public static void remux(File video, @Nullable File audio, File output) throws IOException {
        MediaMuxer muxer = null;
        List<MediaExtractor> extractors = new ArrayList<>(2);
        boolean started = false;
        try {
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            MediaExtractor videoExtractor = open(video);
            extractors.add(videoExtractor);
            SparseIntArray videoTracks = new SparseIntArray();
            int buffer = selectTracks(videoExtractor, muxer, videoTracks, true, audio == null);

            SparseIntArray audioTracks = new SparseIntArray();
            MediaExtractor audioExtractor = null;
            if (audio != null) {
                audioExtractor = open(audio);
                extractors.add(audioExtractor);
                buffer = Math.max(buffer, selectTracks(audioExtractor, muxer, audioTracks, false, true));
            }

            if (videoTracks.size() == 0 && audioTracks.size() == 0) {
                throw new IOException("No usable tracks in the downloaded stream");
            }

            // Both renditions share the HLS timeline, so shift them by one common offset rather
            // than normalising each to zero, which would pull them out of sync.
            long offset = earliestSampleTime(videoExtractor);
            if (audioExtractor != null) {
                long audioStart = earliestSampleTime(audioExtractor);
                if (audioStart >= 0 && (offset < 0 || audioStart < offset)) offset = audioStart;
            }
            if (offset < 0) offset = 0;

            muxer.start();
            started = true;

            int capacity = Math.min(MAX_BUFFER, Math.max(DEFAULT_BUFFER, buffer));
            copy(videoExtractor, muxer, videoTracks, capacity, offset);
            if (audioExtractor != null) copy(audioExtractor, muxer, audioTracks, capacity, offset);

        } finally {
            for (MediaExtractor e : extractors) {
                try {
                    e.release();
                } catch (Exception ignored) {
                }
            }
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (Exception e) {
                    Log.w(TAG, "Muxer stop failed", e);
                }
                try {
                    muxer.release();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static MediaExtractor open(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(file.getAbsolutePath());
        return extractor;
    }

    /**
     * Selects the tracks we want from one extractor and registers them with the muxer.
     *
     * @return the largest max-input-size across the selected tracks
     */
    private static int selectTracks(MediaExtractor extractor, MediaMuxer muxer,
                                    SparseIntArray trackMap, boolean wantVideo, boolean wantAudio)
            throws IOException {
        int maxInput = 0;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null) continue;

            boolean isVideo = mime.startsWith("video/");
            boolean isAudio = mime.startsWith("audio/");
            if (!(isVideo && wantVideo) && !(isAudio && wantAudio)) continue;
            // Only one of each kind: extra tracks would confuse most players.
            if (isVideo && containsMime(extractor, trackMap, "video/")) continue;
            if (isAudio && containsMime(extractor, trackMap, "audio/")) continue;

            extractor.selectTrack(i);
            trackMap.put(i, muxer.addTrack(format));
            if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                maxInput = Math.max(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
        }
        return maxInput;
    }

    private static boolean containsMime(MediaExtractor extractor, SparseIntArray trackMap, String prefix) {
        for (int i = 0; i < trackMap.size(); i++) {
            MediaFormat f = extractor.getTrackFormat(trackMap.keyAt(i));
            String mime = f.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * The earliest presentation timestamp in the stream, then rewinds.
     *
     * <p>Not simply the first sample's, which is the bug this replaces. Samples arrive in decode
     * order, and any stream with B-frames decodes a frame before the frames it is displayed
     * after — so the first sample handed over is an I-frame whose timestamp is <em>later</em> than
     * several of the samples following it. Taking it as the start of the timeline made those
     * samples negative, and they were then flattened to zero: a run of frames all claiming to be
     * at 0:00, which no clock can advance through.
     *
     * <p>That is why this only ever affected some downloads. Baseline and Main profile encodes
     * usually carry no B-frames and came out fine; High profile ones carry them as a matter of
     * course and came out stuck at the first frame.
     *
     * <p>Bounded, because reordering is bounded: the decoded picture buffer holds at most sixteen
     * frames, so the earliest timestamp is always within a few dozen samples of the start. Reading
     * a few hundred costs nothing and cannot be defeated by a long file.
     */
    private static long earliestSampleTime(MediaExtractor extractor) {
        long earliest = -1;
        for (int i = 0; i < REORDER_SCAN; i++) {
            long time = extractor.getSampleTime();
            if (time < 0) break;
            if (earliest < 0 || time < earliest) earliest = time;
            if (!extractor.advance()) break;
        }
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        return earliest;
    }

    private static void copy(MediaExtractor extractor, MediaMuxer muxer, SparseIntArray trackMap,
                             int capacity, long timeOffsetUs) throws IOException {
        if (trackMap.size() == 0) return;

        ByteBuffer buf = ByteBuffer.allocate(capacity);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int size;
            try {
                size = extractor.readSampleData(buf, 0);
            } catch (IllegalArgumentException tooBig) {
                // The sample does not fit. Grow and read the same one again — stopping here is
                // what produced files that ended early and still reported themselves finished,
                // because the caller had no way to tell a truncated copy from a complete one.
                if (buf.capacity() >= MAX_BUFFER) {
                    throw new IOException("A sample exceeds " + MAX_BUFFER + " bytes", tooBig);
                }
                int grown = (int) Math.min(MAX_BUFFER, (long) buf.capacity() * 2);
                Log.i(TAG, "Sample exceeds buffer, growing to " + grown + " bytes");
                buf = ByteBuffer.allocate(grown);
                continue;
            }
            if (size < 0) return;

            int outTrack = trackMap.get(extractor.getSampleTrackIndex(), -1);
            if (outTrack >= 0) {
                long pts = extractor.getSampleTime() - timeOffsetUs;
                // A safety net only. With the offset taken from the earliest timestamp rather
                // than the first, nothing should land before zero — and flattening several
                // samples onto zero is precisely what broke playback before.
                if (pts < 0) pts = 0;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                // MediaExtractor's flag set is not MediaCodec's; only sync frames map across.
                info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(outTrack, buf, info);
            }
            if (!extractor.advance()) return;
        }
    }
}
