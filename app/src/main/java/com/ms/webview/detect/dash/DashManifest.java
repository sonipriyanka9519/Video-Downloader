package com.ms.webview.detect.dash;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parsed MPD. One {@link Representation} per encoded rendition. */
public class DashManifest {

    public static class Representation {
        public String id;
        public String url;
        public String mimeType;
        public String codecs;
        public int width;
        public int height;
        public long bandwidth;
        public boolean video;

        public int heightOrEstimate() {
            if (height > 0) return height;
            if (bandwidth >= 4_000_000) return 1080;
            if (bandwidth >= 2_000_000) return 720;
            if (bandwidth >= 1_000_000) return 480;
            if (bandwidth >= 500_000) return 360;
            return 240;
        }
    }

    public final List<Representation> videos = new ArrayList<>();
    public final List<Representation> audios = new ArrayList<>();
    public long durationMs;
    public boolean drmProtected;
    /**
     * True when representations are described by segment templates rather than a whole-file
     * BaseURL. Instagram and Facebook use the whole-file form; the templated form needs a
     * segment assembler we do not have yet.
     */
    public boolean templated;

    public boolean usable() {
        return !drmProtected && !videos.isEmpty();
    }

    /** Highest-bitrate separate audio track, or null when the manifest names none. */
    @Nullable
    public Representation bestAudio() {
        Representation best = null;
        for (Representation r : audios) {
            if (best == null || r.bandwidth > best.bandwidth) best = r;
        }
        return best;
    }

    /**
     * Whether the video renditions already contain their sound.
     *
     * <p>Worth asking separately, because {@link #bestAudio()} returning null has two very
     * different meanings and the caller used to read only the harmless one. A manifest of muxed
     * renditions names no audio because none is needed. A manifest whose audio we failed to
     * recognise also names no audio — and there the video is silent, and downloading it as an
     * ordinary file produces a mute result nothing downstream can detect.
     *
     * <p>The codecs list settles it: a muxed rendition declares both, as
     * {@code codecs="avc1.4d401f,mp4a.40.2"}. Where nothing is declared at all we assume muxed,
     * which is the older single-stream shape and the safer guess for a rendition we know nothing
     * about.
     */
    public boolean videoCarriesAudio() {
        for (Representation r : videos) {
            if (r.codecs == null || r.codecs.trim().isEmpty()) return true;
            String codecs = r.codecs.toLowerCase(Locale.US);
            if (codecs.contains("mp4a") || codecs.contains("opus") || codecs.contains("vorbis")
                    || codecs.contains("ac-3") || codecs.contains("ec-3")
                    || codecs.contains("flac")) {
                return true;
            }
        }
        return false;
    }
}
