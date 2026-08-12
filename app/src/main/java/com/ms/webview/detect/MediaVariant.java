package com.ms.webview.detect;

import androidx.annotation.NonNull;

import com.ms.webview.core.Formats;

import java.util.HashMap;
import java.util.Map;

/** One downloadable rendition of a {@link MediaItem}. */
public class MediaVariant {

    public final String url;
    public MediaKind kind;
    public String mime;
    public int width;
    public int height;
    /** A measured byte count only. Estimates are derived on demand, never stored here. */
    public long sizeBytes;
    public boolean acceptsRanges;
    public boolean probed;
    /**
     * Set once we have actually fetched from this URL with a known-good header set. Only
     * verified variants reach the sheet, so anything the user can tap has already proved it
     * downloads.
     */
    public boolean verified;
    /** True once the metadata pass has run, whether or not it learned anything. */
    public boolean inspected;
    /** True when this exact URL was opened and decoded as video, not merely reachable. */
    public boolean decoded;

    /** HLS: bits per second advertised by the master playlist. */
    public long bandwidth;
    /** HLS: separate audio playlist to be muxed in, when the rendition is video-only. */
    public String audioUrl;
    /**
     * A master playlist stays in the item so we do not re-resolve it, but never reaches the UI —
     * the user picks one of the renditions it expanded into.
     */
    public boolean hidden;

    /** Headers captured when the WebView requested this URL. Replayed at download time. */
    public final Map<String, String> headers = new HashMap<>();

    public MediaVariant(@NonNull String url, MediaKind kind) {
        this.url = url;
        this.kind = kind;
    }

    /** The rung this rendition sits on — its shorter side, snapped to a standard size. */
    public int qualityRung() {
        return Formats.qualityRung(width, height);
    }

    /** Top line of a quality card: "1080p", or a bitrate when the dimensions are unknown. */
    public String qualityName() {
        String q = Formats.quality(width, height);
        if (!q.isEmpty()) return q;
        if (bandwidth > 0) return Math.round(bandwidth / 1000d) + "k";
        return "Original";
    }

    /** True for a portrait video, which needs a taller preview than a 16:9 frame. */
    public boolean portrait() {
        return width > 0 && height > width;
    }

    /**
     * How big this rendition is, in bytes.
     *
     * <p>Derived at the point of use rather than stored, and that matters. Estimates used to be
     * baked into {@code sizeBytes} the moment a manifest was parsed — but the duration was often
     * still unknown then, so some renditions got a figure and others did not, and the ones that
     * did were computed against whatever duration happened to be known at that instant. Within
     * one video that produced sizes that disagreed with each other: a 720p reading smaller than
     * the 540p beneath it. Computing from one duration, now, keeps the ladder consistent.
     *
     * @param durationMs the video's running time, from the item that owns this variant
     */
    public long sizeFor(long durationMs) {
        if (sizeBytes > 0) return sizeBytes;
        if (bandwidth > 0 && durationMs > 0) {
            return (long) (bandwidth / 8d * (durationMs / 1000d));
        }
        return 0;
    }

    /**
     * Second line of a quality card: just the size.
     *
     * <p>The container was dropped deliberately — every download ends up as an MP4 whatever it
     * arrived as, so naming the delivery format told the user nothing and only crowded the card.
     */
    public String qualityMeta(long durationMs) {
        long size = sizeFor(durationMs);
        return size > 0 ? Formats.bytes(size) : "";
    }

    /** Higher is better. Drives the default selection and the order of the quality grid. */
    public int rank() {
        int rung = qualityRung();
        if (rung > 0) return rung;
        if (bandwidth > 0) return (int) Math.min(Integer.MAX_VALUE, bandwidth / 5000);
        if (sizeBytes > 0) return (int) Math.min(Integer.MAX_VALUE, sizeBytes / 100_000);
        return 0;
    }

    @NonNull
    @Override
    public String toString() {
        return qualityName() + ' ' + Formats.bytes(sizeBytes);
    }
}
