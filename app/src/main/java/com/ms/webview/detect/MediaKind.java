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
    /**
     * A complete sound track with no picture, offered on its own.
     *
     * <p>Only ever set by opening a stream and finding a running time, a sound track and no
     * dimensions — never guessed from a content type, because the platforms that serve sound
     * separately label it {@code video/mp4}. It is the same stream that gets muxed into a
     * video-only rendition; offering it by itself costs nothing extra, since it has already been
     * found, measured and proved fetchable by then.
     */
    AUDIO,
    /** A .ts/.m4s segment. Never queued directly; only a hint that a manifest exists. */
    SEGMENT,
    /** Looks plausible but needs a probe to confirm it is really video. */
    UNKNOWN,
    /** Not media. */
    NONE;

    public boolean downloadable() {
        return this == PROGRESSIVE || this == HLS || this == DASH || this == AUDIO;
    }

    /**
     * Whether this carries a picture.
     *
     * <p>The distinction {@link #downloadable()} cannot make. Plenty of places ask "can the user
     * press this" and mean it, but a few — what counts as having confirmed a video, what the
     * download button defaults to, whether an item is a video at all — mean "is there something
     * to watch", and to those a sound track must answer no.
     */
    public boolean visual() {
        return this == PROGRESSIVE || this == HLS || this == DASH;
    }
}
