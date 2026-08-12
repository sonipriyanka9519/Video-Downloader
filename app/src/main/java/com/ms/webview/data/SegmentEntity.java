package com.ms.webview.data;

/**
 * One HLS segment. Segment-level checkpointing is the HLS equivalent of the byte ranges a
 * progressive download resumes from: a killed process picks up at the next missing segment.
 */
public class SegmentEntity {

    public static final int TRACK_VIDEO = 0;
    public static final int TRACK_AUDIO = 1;

    public long id;

    public long downloadId;
    /** {@link #TRACK_VIDEO} for the muxed or video-only stream, {@link #TRACK_AUDIO} otherwise. */
    public int track;
    /** Position within the track. Index 0 is the EXT-X-MAP init segment when there is one. */
    public int seq;

    public String url;
    /** AES-128 key URL, or null when the segment is in the clear. */
    public String keyUri;
    /** Hex IV from the playlist, or null to derive it from the media sequence number. */
    public String iv;
    public long mediaSequence;

    public long byteStart = -1;
    public long byteLength = -1;

    public boolean done;
    /** Size on disk once written, used to refine the total-size estimate as we go. */
    public long bytes;

    public SegmentEntity() {
    }

    public SegmentEntity(long downloadId, int track, int seq, String url) {
        this.downloadId = downloadId;
        this.track = track;
        this.seq = seq;
        this.url = url;
    }

    public String fileName() {
        return String.format(java.util.Locale.US, "t%d_%06d.part", track, seq);
    }
}
