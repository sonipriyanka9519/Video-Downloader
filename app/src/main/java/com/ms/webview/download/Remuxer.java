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

    /**
     * How far apart two tracks may start and still be read as one clock.
     *
     * <p>Ten seconds is far wider than any real head start between renditions of one stream —
     * those are a segment apart at most — and far narrower than the gap that appears when the
     * two are not on the same clock at all, which is the whole of a broadcast PTS: minutes, and
     * routinely hours.
     */
    private static final long SHARED_TIMELINE_TOLERANCE_US = 10_000_000L;

    /** See checkAudioIsWholeOrFail. */
    private static final int MAX_SAMPLES_PER_AAC_FRAME = 2048;
    private static final double MIN_AUDIO_COMPLETENESS = 0.35d;
    private static final long MIN_AUDIO_SPAN_FOR_CHECK_US = 3_000_000L;

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

            // Refuse to produce a silent file from a stream that had sound in it.
            //
            // Dailymotion's renditions are muxed MPEG-TS — the master advertises
            // CODECS="mp4a.40.2,avc1.64001f" and the segments are named h264_aac — so there is no
            // separate audio to fetch and none to pair. The sound is already in the bytes we
            // downloaded. If it is present in the source and did not make it into the track map,
            // something between the extractor and the muxer dropped it, and carrying on writes an
            // MP4 that plays perfectly and says nothing.
            //
            // Failing here is what makes that recoverable: the caller keeps the raw transport
            // stream instead, which still has the audio and which nearly every player opens.
            // A .ts with sound beats an .mp4 without.
            boolean sourceHasAudio = hasAudioTrack(videoExtractor)
                    || (audioExtractor != null && hasAudioTrack(audioExtractor));
            boolean carryingAudio = mapsAudio(videoExtractor, videoTracks)
                    || (audioExtractor != null && mapsAudio(audioExtractor, audioTracks));
            Log.i(TAG, "Tracks: " + describe(videoExtractor)
                    + (audioExtractor == null ? "" : " + " + describe(audioExtractor))
                    + " -> carrying audio: " + carryingAudio);
            if (sourceHasAudio && !carryingAudio) {
                throw new IOException("The stream has an audio track but it could not be muxed");
            }

            long videoStart = earliestSampleTime(video, true, audio == null);
            long audioStart = audio == null ? -1 : earliestSampleTime(audio, false, true);
            long videoOffset = videoStart < 0 ? 0 : videoStart;
            long audioOffset = audioStart < 0 ? 0 : audioStart;

            if (audio != null && videoStart >= 0 && audioStart >= 0) {
                // One offset for both, or one each — and getting this wrong is what put the
                // picture in the second half of its own file.
                //
                // Two renditions of one HLS stream share a clock, and there the difference
                // between their first samples is real: it is the genuine head start of one track
                // over the other, and normalising each to zero would destroy the sync by exactly
                // that much. So where they are close, both move by the smaller of the two.
                //
                // But they do not always share a clock. Audio delivered as raw AAC has no
                // timestamps of its own, so the extractor synthesises them from zero, while the
                // video arrives as MPEG-TS carrying a real broadcast PTS that can be hours in.
                // Taking the minimum then left the video shifted by the whole of that PTS: the
                // picture began far into the timeline, with the sound stranded at the start
                // where there was nothing to see. Which is one fault presenting as two — a video
                // that starts halfway through, and a video with no voice over it.
                //
                // A gap this large is not a head start, because no rendition of the same stream
                // opens minutes after its partner. It is two unrelated clocks, and the only
                // sane reading is that each begins at its own beginning.
                if (Math.abs(videoStart - audioStart) <= SHARED_TIMELINE_TOLERANCE_US) {
                    long shared = Math.min(videoStart, audioStart);
                    videoOffset = shared;
                    audioOffset = shared;
                } else {
                    Log.i(TAG, "Video starts at " + videoStart + "us and audio at " + audioStart
                            + "us: separate clocks, each normalised to its own start");
                }
            }

            muxer.start();
            started = true;

            int capacity = Math.min(MAX_BUFFER, Math.max(DEFAULT_BUFFER, buffer));
            AudioTally tally = new AudioTally();
            copy(videoExtractor, muxer, videoTracks, capacity, videoOffset, tally);
            if (audioExtractor != null) {
                copy(audioExtractor, muxer, audioTracks, capacity, audioOffset, tally);
            }
            checkAudioIsWholeOrFail(tally);

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

    /** What the audio copy actually managed to write, gathered as it goes. */
    private static final class AudioTally {
        int samples;
        int sampleRate;
        long firstUs = -1;
        long lastUs = -1;

        void note(long presentationTimeUs) {
            samples++;
            if (firstUs < 0 || presentationTimeUs < firstUs) firstUs = presentationTimeUs;
            if (presentationTimeUs > lastUs) lastUs = presentationTimeUs;
        }

        long spanUs() {
            return firstUs < 0 || lastUs <= firstUs ? 0 : lastUs - firstUs;
        }
    }

    /**
     * Fails the remux when the sound that came out is too sparse to be the sound that went in.
     *
     * <p>The reason this is needed at all: {@link MediaExtractor} reading a concatenated MPEG-TS
     * quietly loses most of the audio. A Dailymotion download came out with 139 AAC frames spread
     * over 115 seconds, where a 22050 Hz track that long holds around 2,480. The file was not
     * silent and not truncated — it had a complete picture and about five percent of its
     * soundtrack, with the surviving frames stretched across the whole duration.
     *
     * <p>Which is why it looked like a player bug. ExoPlayer takes its master clock from the audio
     * renderer whenever audio is present, so a track advancing in near-second lurches leaves the
     * clock stuck at 00:00 and the renderer permanently starved — the video never starts. Other
     * players fall back to the video clock and play it, which is what made the file look innocent.
     *
     * <p>Nothing downstream could catch it. The track is present, so "had audio, carried none"
     * does not fire; the duration is right, so nothing looks truncated. Counting the frames is the
     * only test that separates a real soundtrack from a handful of survivors.
     *
     * <p>Deliberately generous. Expected frames are computed against 2048 samples per frame — the
     * larger, HE-AAC figure — so an ordinary AAC-LC track scores about 2.0 and even HE-AAC scores
     * 1.0. Anything above a third of that passes. The file that prompted this scored 0.11.
     */
    private static void checkAudioIsWholeOrFail(AudioTally tally) throws IOException {
        if (tally.samples == 0 || tally.sampleRate <= 0) return;

        long spanUs = tally.spanUs();
        // Too short to reason about: a couple of seconds of audio is a handful of frames either
        // way, and the ratio is noise at that length.
        if (spanUs < MIN_AUDIO_SPAN_FOR_CHECK_US) return;

        double spanSeconds = spanUs / 1_000_000d;
        double expected = spanSeconds * tally.sampleRate / MAX_SAMPLES_PER_AAC_FRAME;
        if (expected <= 0) return;

        double ratio = tally.samples / expected;
        if (ratio >= MIN_AUDIO_COMPLETENESS) return;

        throw new IOException("The audio track is incomplete: " + tally.samples
                + " frames across " + Math.round(spanSeconds) + "s at " + tally.sampleRate
                + "Hz, about " + Math.round(ratio * 100) + "% of a whole track");
    }

    /** Whether the source itself declares a sound track, whatever became of it afterwards. */
    private static boolean hasAudioTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) return true;
        }
        return false;
    }

    /** Whether a sound track actually reached the muxer. */
    private static boolean mapsAudio(MediaExtractor extractor, SparseIntArray trackMap) {
        return containsMime(extractor, trackMap, "audio/");
    }

    /** Every track the source declares, for the log. */
    private static String describe(MediaExtractor extractor) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME));
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
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
    private static long earliestSampleTime(File file, boolean wantVideo, boolean wantAudio)
            throws IOException {
        // Scanned on an extractor of its own, thrown away afterwards, so the one that does the
        // copying is never moved.
        //
        // It used to scan the real extractor and rewind with seekTo(0). A concatenated HLS
        // stream rarely starts at zero — an MPEG-TS timeline begins wherever the broadcaster's
        // PTS happened to be — so that asked for the sync sample before the start of the file,
        // which is undefined and which MediaExtractor answers differently for TS on different
        // devices. Where it landed past the opening, everything before that point was dropped
        // and the video began partway through.
        MediaExtractor scanner = open(file);
        try {
            // The same tracks the copy will carry, so the offset is the earliest of exactly what
            // gets written — not of a track that is about to be discarded.
            for (int i = 0; i < scanner.getTrackCount(); i++) {
                String mime = scanner.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime == null) continue;
                if (mime.startsWith("video/") && wantVideo) scanner.selectTrack(i);
                else if (mime.startsWith("audio/") && wantAudio) scanner.selectTrack(i);
            }
            long earliest = -1;
            for (int i = 0; i < REORDER_SCAN; i++) {
                long time = scanner.getSampleTime();
                if (time < 0) break;
                if (earliest < 0 || time < earliest) earliest = time;
                if (!scanner.advance()) break;
            }
            return earliest;
        } finally {
            try {
                scanner.release();
            } catch (Exception ignored) {
            }
        }
    }

    private static void copy(MediaExtractor extractor, MediaMuxer muxer, SparseIntArray trackMap,
                             int capacity, long timeOffsetUs, AudioTally tally) throws IOException {
        if (trackMap.size() == 0) return;

        // Which of this extractor's tracks is the sound, resolved once rather than per sample.
        // -1 when this extractor is carrying only a picture, which is the normal case for the
        // video half of a two-file mux.
        int audioTrack = -1;
        for (int i = 0; i < trackMap.size(); i++) {
            int input = trackMap.keyAt(i);
            MediaFormat format = extractor.getTrackFormat(input);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("audio/")) continue;
            audioTrack = input;
            if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                tally.sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            }
            break;
        }

        ByteBuffer buf = ByteBuffer.allocate(capacity);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int flattened = 0;
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
            if (size < 0) break;

            int inTrack = extractor.getSampleTrackIndex();
            int outTrack = trackMap.get(inTrack, -1);
            if (outTrack >= 0) {
                long pts = extractor.getSampleTime() - timeOffsetUs;
                // A safety net only. With the offset taken from the earliest timestamp rather
                // than the first, nothing should land before zero — and flattening several
                // samples onto zero is precisely what broke playback before.
                //
                // Counted and reported rather than done quietly. A stream carrying a timestamp
                // discontinuity — segments whose clock restarts partway through — has a genuine
                // minimum later than anything the opening scan can see, and every sample after
                // the reset lands here. That is a run of frames all claiming 0:00, which is the
                // same broken playback by a different route, and it should not be silent.
                if (pts < 0) {
                    pts = 0;
                    flattened++;
                }
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = pts;
                // MediaExtractor's flag set is not MediaCodec's; only sync frames map across.
                info.flags = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0;
                muxer.writeSampleData(outTrack, buf, info);
                // Counted after the write, so the tally describes what is actually in the file.
                if (inTrack == audioTrack) tally.note(pts);
            }
            if (!extractor.advance()) break;
        }

        if (flattened > 0) {
            Log.w(TAG, flattened + " samples fell before the start of the timeline and were "
                    + "pinned to zero. The stream's clock restarts partway through, so the "
                    + "opening scan could not see its real minimum.");
        }
    }
}
