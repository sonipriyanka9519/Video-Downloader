package com.ms.webview.detect;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Http;
import com.ms.webview.detect.dash.DashManifest;
import com.ms.webview.detect.dash.DashParser;
import com.ms.webview.detect.hls.HlsHttp;

import java.util.Map;

/**
 * Turns an MPD into the per-quality list the user picks from.
 *
 * <p>This is where Instagram and Facebook reels finally get more than one quality. Their
 * progressive URLs are a single encode, but the DASH manifest they embed in their own JSON
 * lists every rendition they hold — which is exactly the ladder the app was missing.
 *
 * <p>The manifest usually arrives as inline XML rather than a URL, so it never has to be
 * fetched at all.
 */
public class DashResolver {

    private static final String TAG = "DashResolver";

    public interface Callback {
        void onResolved(MediaItem item);
    }

    /** Resolves a manifest referenced by URL. */
    public void resolve(MediaItem item, MediaVariant source, Callback callback) {
        try {
            // Asked before fetching, for the same reason as in the HLS resolver: a protected
            // manifest often cannot be read without a licence, so the parse finds no encryption
            // to report and the stream reads as broken rather than protected.
            if (UrlClassifier.declaresDrm(source.url)) {
                item.drmProtected = true;
                source.kind = MediaKind.NONE;
                return;
            }

            String xml = fetch(source);
            apply(item, source, DashParser.parse(xml, source.url), source.headers);
        } catch (Exception e) {
            Log.w(TAG, "Could not resolve " + source.url, e);
            source.kind = MediaKind.NONE;
        } finally {
            source.probed = true;
            callback.onResolved(item);
        }
    }

    /**
     * Resolves a manifest we already have the text of.
     *
     * @param baseUrl what relative BaseURLs resolve against — the page, when the manifest was
     *                embedded in its JSON rather than served on its own
     */
    public void resolveInline(MediaItem item, String xml, String baseUrl,
                              @Nullable Map<String, String> headers) {
        try {
            apply(item, null, DashParser.parse(xml, baseUrl), headers);
        } catch (Exception e) {
            Log.w(TAG, "Could not parse inline manifest", e);
        }
    }

    private void apply(MediaItem item, @Nullable MediaVariant source, DashManifest manifest,
                       @Nullable Map<String, String> headers) {
        if (manifest.drmProtected) {
            item.drmProtected = true;
            if (source != null) source.kind = MediaKind.NONE;
            return;
        }
        if (!manifest.usable()) {
            // Either empty, or built from segment templates we cannot assemble yet.
            if (source != null) {
                source.kind = manifest.templated ? MediaKind.NONE : MediaKind.NONE;
            }
            return;
        }

        // The manifest itself is not downloadable; the renditions it names are.
        if (source != null) {
            source.hidden = true;
            source.probed = true;
        }

        DashManifest.Representation audio = manifest.bestAudio();
        String audioUrl = audio == null ? null : audio.url;
        long audioBandwidth = audio == null ? 0 : audio.bandwidth;

        if (manifest.durationMs > 0 && item.durationMs <= 0) item.durationMs = manifest.durationMs;

        for (DashManifest.Representation video : manifest.videos) {
            if (TextUtils.isEmpty(video.url)) continue;

            // A rendition with its own audio needs no muxing, so treat it as an ordinary file.
            boolean needsMux = audioUrl != null;
            MediaVariant v = item.addOrGet(video.url,
                    needsMux ? MediaKind.DASH : MediaKind.PROGRESSIVE);
            v.kind = needsMux ? MediaKind.DASH : MediaKind.PROGRESSIVE;
            v.probed = true;
            // Parsing the manifest is the platform telling us these exist; the download path
            // falls back through relaxed headers if a particular one is refused.
            v.verified = true;
            v.audioUrl = audioUrl;
            v.mime = "video/mp4";
            v.width = video.width;
            v.height = video.heightOrEstimate();
            // Includes the audio track, because the download muxes both and the size shown
            // should be what actually lands on disk.
            v.bandwidth = video.bandwidth + audioBandwidth;

            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (!v.headers.containsKey(e.getKey())) v.headers.put(e.getKey(), e.getValue());
                }
            }
        }
        item.videoConfirmed = true;
        item.sortVariants();
    }

    private String fetch(MediaVariant variant) throws Exception {
        try {
            return HlsHttp.fetchText(variant.url, variant.headers);
        } catch (HlsHttp.StatusException status) {
            if (!Http.deniedByHeaders(status.code)) throw status;
            Map<String, String> relaxed = Http.relaxed(variant.headers);
            String xml = HlsHttp.fetchText(variant.url, relaxed);
            variant.headers.clear();
            variant.headers.putAll(relaxed);
            return xml;
        }
    }
}
