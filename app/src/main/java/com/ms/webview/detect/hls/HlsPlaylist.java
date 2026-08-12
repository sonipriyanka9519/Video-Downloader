package com.ms.webview.detect.hls;

import java.util.ArrayList;
import java.util.List;

/** Parsed form of an m3u8, either a master (list of renditions) or a media playlist (segments). */
public class HlsPlaylist {

    /** One quality in a master playlist. This is what becomes a selectable variant in the UI. */
    public static class Rendition {
        public String url;
        public long bandwidth;
        public int width;
        public int height;
        public String codecs;
        /** Set when the audio for this rendition lives in a separate playlist. */
        public String audioUrl;
        /**
         * The {@code AUDIO} group this rendition names, resolved to {@link #audioUrl} once the
         * whole playlist has been read.
         *
         * <p>Held rather than resolved on sight because a master playlist may declare its
         * {@code EXT-X-MEDIA} groups after the streams that reference them — the format does not
         * fix the order, and some CDNs put them last.
         */
        public String audioGroup;

        public int heightOrEstimate() {
            if (height > 0) return height;
            // Rough ladder mapping so renditions without RESOLUTION still sort sensibly.
            if (bandwidth >= 5_000_000) return 1080;
            if (bandwidth >= 2_500_000) return 720;
            if (bandwidth >= 1_200_000) return 480;
            if (bandwidth >= 600_000) return 360;
            return 240;
        }
    }

    public static class Segment {
        public String url;
        public double durationSec;
        /** AES-128 key location, or null when the segment is in the clear. */
        public String keyUri;
        /** Hex IV from the playlist, or null to derive it from the sequence number. */
        public String ivHex;
        public long mediaSequence;
        public long byteStart = -1;
        public long byteLength = -1;
    }

    public boolean master;
    /** No EXT-X-ENDLIST and not marked VOD: a live edge we cannot meaningfully archive. */
    public boolean live;
    /** SAMPLE-AES or a non-identity KEYFORMAT — Widevine/FairPlay territory. */
    public boolean drmProtected;

    public final List<Rendition> renditions = new ArrayList<>();
    public final List<Segment> segments = new ArrayList<>();

    /** EXT-X-MAP init segment for fragmented-MP4 playlists. */
    public String initSegmentUrl;
    public long initByteStart = -1;
    public long initByteLength = -1;

    public double totalDurationSec;

    public boolean hasSegments() {
        return !segments.isEmpty();
    }

    public long durationMs() {
        return (long) (totalDurationSec * 1000);
    }
}
