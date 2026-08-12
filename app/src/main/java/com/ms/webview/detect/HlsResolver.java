package com.ms.webview.detect;

import android.util.Log;

import com.ms.webview.core.Http;
import com.ms.webview.detect.hls.HlsHttp;
import com.ms.webview.detect.hls.HlsParser;
import com.ms.webview.detect.hls.HlsPlaylist;

import java.util.List;
import java.util.Map;

/**
 * Turns one detected {@code .m3u8} URL into the per-quality list the user picks from.
 *
 * <p>A master playlist is fetched, its {@code EXT-X-STREAM-INF} renditions become individual
 * variants on the same item, and the master itself is hidden. A media playlist is a single
 * quality and is kept as-is. Either way the item ends up with a real duration and a bandwidth
 * per quality, which is what the sheet turns into a size.
 */
public class HlsResolver {

    private static final String TAG = "HlsResolver";

    public interface Callback {
        void onResolved(MediaItem item);
    }

    /**
     * Runs on a background thread. Mutates {@code item} and reports back so the registry can
     * republish.
     */
    public void resolve(MediaItem item, MediaVariant source, Callback callback) {
        try {
            // Asked before fetching. A protected stream's manifest is often unreadable without a
            // licence, so the parse below would find no encryption to report and the stream would
            // read as broken rather than protected — leaving the sheet to try, and fail, on every
            // segment behind it.
            if (UrlClassifier.declaresDrm(source.url)) {
                item.drmProtected = true;
                source.kind = MediaKind.NONE;
                return;
            }

            String text = fetchPlaylist(source);
            HlsPlaylist playlist = HlsParser.parse(text, source.url);

            if (playlist.drmProtected) {
                // Keep the row visible but unusable, so the sheet explains itself.
                item.drmProtected = true;
                source.kind = MediaKind.NONE;
                return;
            }

            if (playlist.master && !playlist.renditions.isEmpty()) {
                expandMaster(item, source, playlist);
            } else if (playlist.hasSegments()) {
                describeMedia(item, source, playlist);
            } else {
                // Fetched and parsed, but it named neither renditions nor segments. The item
                // keeps its metadata and loses its only source, which is the state the sheet
                // reports as detected-but-unopenable.
                Log.i(MediaRegistry.DIAG, "hls empty playlist (master=" + playlist.master
                        + ") " + source.url);
                source.kind = MediaKind.NONE;
            }
        } catch (Exception e) {
            // Under the detector's own tag as well. This is the whole reason an item can sit in
            // the sheet saying it could not be opened, and logging it only under HlsResolver
            // meant the one line explaining that never appeared beside the rest of the story.
            Log.i(MediaRegistry.DIAG, "hls resolve failed: " + e + " for " + source.url);
            Log.w(TAG, "Could not resolve " + source.url, e);
            source.kind = MediaKind.NONE;
        } finally {
            source.probed = true;
            callback.onResolved(item);
        }
    }

    /**
     * Fetches a playlist, falling back to relaxed headers when the CDN refuses the captured
     * ones — and keeping the working set on the variant so the download reuses it.
     */
    private String fetchPlaylist(MediaVariant variant) throws Exception {
        try {
            return HlsHttp.fetchText(variant.url, variant.headers);
        } catch (HlsHttp.StatusException status) {
            if (!Http.deniedByHeaders(status.code)) throw status;
            Map<String, String> relaxed = Http.relaxed(variant.headers);
            String text = HlsHttp.fetchText(variant.url, relaxed);
            variant.headers.clear();
            variant.headers.putAll(relaxed);
            return text;
        }
    }

    private void expandMaster(MediaItem item, MediaVariant master, HlsPlaylist playlist) {
        // The master is not itself downloadable — the user picks one of its renditions.
        master.hidden = true;
        master.probed = true;

        for (HlsPlaylist.Rendition r : playlist.renditions) {
            MediaVariant v = item.addOrGet(r.url, MediaKind.HLS);
            v.kind = MediaKind.HLS;
            v.probed = true;
            // Fetching the master with these headers proves the CDN accepts them, and every
            // rendition sits behind the same signature.
            v.verified = true;
            v.bandwidth = r.bandwidth;
            v.audioUrl = r.audioUrl;
            if (r.width > 0) v.width = r.width;
            v.height = r.heightOrEstimate();
            v.mime = "video/mp4";
            v.headers.putAll(master.headers);
        }

        // One media playlist is enough to learn how long the video is.
        long durationMs = item.durationMs;
        if (durationMs <= 0) durationMs = probeDuration(playlist, master.headers);
        // Sizes are not written here: each rendition keeps its bandwidth and the figure is
        // derived from the item duration at display time, so the whole ladder agrees.
        if (durationMs > 0) item.durationMs = durationMs;
        // A master playlist listing renditions is a video by definition; its siblings need no
        // separate decode to be trusted.
        item.videoConfirmed = true;
        item.sortVariants();
    }

    private void describeMedia(MediaItem item, MediaVariant variant, HlsPlaylist playlist) {
        if (playlist.live) {
            // A live edge has no end to download to.
            variant.kind = MediaKind.NONE;
            return;
        }
        variant.kind = MediaKind.HLS;
        variant.mime = "video/mp4";
        variant.verified = true;
        if (item.durationMs <= 0) item.durationMs = playlist.durationMs();

        if (variant.sizeBytes <= 0 && !playlist.segments.isEmpty()) {
            // Extrapolate from the first segment rather than measuring all of them.
            HlsPlaylist.Segment first = playlist.segments.get(0);
            long firstSize = first.byteLength > 0
                    ? first.byteLength
                    : HlsHttp.sizeOf(first.url, variant.headers);
            if (firstSize > 0) {
                variant.sizeBytes = firstSize * playlist.segments.size();
            }
        }
    }

    /** Fetches the smallest rendition purely to learn how long the video is. */
    private long probeDuration(HlsPlaylist master, Map<String, String> headers) {
        HlsPlaylist.Rendition smallest = null;
        for (HlsPlaylist.Rendition r : master.renditions) {
            if (smallest == null || r.bandwidth < smallest.bandwidth) smallest = r;
        }
        if (smallest == null) return 0;
        try {
            HlsPlaylist media = HlsParser.parse(
                    HlsHttp.fetchText(smallest.url, headers), smallest.url);
            if (media.live) return 0;
            return media.durationMs();
        } catch (Exception e) {
            Log.w(TAG, "Duration probe failed", e);
            return 0;
        }
    }

}
