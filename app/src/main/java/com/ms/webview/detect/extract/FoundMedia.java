package com.ms.webview.detect.extract;

import com.ms.webview.detect.MediaKind;

/** One video URL recovered from a platform's own JSON, with whatever context came with it. */
public class FoundMedia {

    public String url;
    public MediaKind kind = MediaKind.PROGRESSIVE;
    public String thumbnail;
    public String title;
    public String author;
    public int width;
    public int height;
    public long bitrate;
    public long durationMs;

    /**
     * Post/media id. Every quality of one video carries the same hint, which is how several
     * URLs collapse into a single card with a quality picker rather than several cards.
     */
    public String groupHint;

    /**
     * A DASH manifest embedded in the response as XML rather than linked by URL. Instagram and
     * Facebook ship the whole quality ladder this way, so it never needs fetching.
     */
    public String inlineManifest;

    /**
     * A separate audio track to be muxed in. Reddit and Bilibili publish video-only renditions
     * this way, and downloading one without its companion produces a silent file.
     */
    public String audioUrl;

    public FoundMedia(String url) {
        this.url = url;
    }

    public boolean valid() {
        return url != null && url.startsWith("http");
    }

    public boolean hasInlineManifest() {
        return inlineManifest != null && inlineManifest.contains("<MPD");
    }
}
