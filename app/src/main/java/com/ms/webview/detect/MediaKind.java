package com.ms.webview.detect;

public enum MediaKind {
    /** A single self-contained file we can fetch with HTTP Range requests. */
    PROGRESSIVE,
    /** An HLS media playlist: a segment list we can fetch, decrypt and remux into an MP4. */
    HLS,
    /**
     * A DASH rendition: a complete video-only file that needs the matching audio track muxed
     * in. The manifest that named it is where the quality ladder comes from.
     */
    DASH,
    /** A .ts/.m4s segment. Never queued directly; only a hint that a manifest exists. */
    SEGMENT,
    /** Looks plausible but needs a probe to confirm it is really video. */
    UNKNOWN,
    /** Not media. */
    NONE;

    public boolean downloadable() {
        return this == PROGRESSIVE || this == HLS || this == DASH;
    }
}
