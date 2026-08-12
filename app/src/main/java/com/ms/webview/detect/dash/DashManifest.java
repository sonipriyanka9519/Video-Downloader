package com.ms.webview.detect.dash;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

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

    /** Highest-bitrate audio track, or null when the video representations carry their own. */
    @Nullable
    public Representation bestAudio() {
        Representation best = null;
        for (Representation r : audios) {
            if (best == null || r.bandwidth > best.bandwidth) best = r;
        }
        return best;
    }
}
