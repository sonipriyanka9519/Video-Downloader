package com.ms.webview.detect.hls;

import android.text.TextUtils;

import java.net.URL;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal but complete-enough m3u8 parser: master renditions, segment lists, AES-128 keys,
 * byte ranges, fMP4 init segments, and the two markers that mean "do not download this"
 * (live edge, and DRM).
 */
public final class HlsParser {

    private HlsParser() {
    }

    public static HlsPlaylist parse(String text, String baseUrl) {
        HlsPlaylist playlist = new HlsPlaylist();
        if (TextUtils.isEmpty(text)) return playlist;

        // group id -> audio playlist url, from EXT-X-MEDIA
        Map<String, String> audioGroups = new HashMap<>();

        boolean endList = false;
        boolean vod = false;
        long mediaSequence = 0;
        long segmentIndex = 0;

        HlsPlaylist.Rendition pendingRendition = null;
        double pendingDuration = 0;
        long pendingByteStart = -1;
        long pendingByteLength = -1;
        long previousByteEnd = 0;

        String currentKeyUri = null;
        String currentKeyIv = null;

        // A master may advertise several key systems side by side — see the EXT-X-SESSION-KEY
        // handling below — so the verdict is taken once, after all of them have been seen.
        boolean sessionKeyDrm = false;
        boolean sessionKeyOpen = false;

        String[] lines = text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (!line.startsWith("#")) {
                String url = resolve(baseUrl, line);
                if (pendingRendition != null) {
                    pendingRendition.url = url;
                    playlist.renditions.add(pendingRendition);
                    playlist.master = true;
                    pendingRendition = null;
                } else {
                    HlsPlaylist.Segment s = new HlsPlaylist.Segment();
                    s.url = url;
                    s.durationSec = pendingDuration;
                    s.keyUri = currentKeyUri;
                    s.ivHex = currentKeyIv;
                    s.mediaSequence = mediaSequence + segmentIndex;
                    if (pendingByteLength > 0) {
                        s.byteStart = pendingByteStart >= 0 ? pendingByteStart : previousByteEnd;
                        s.byteLength = pendingByteLength;
                        previousByteEnd = s.byteStart + s.byteLength;
                    }
                    playlist.segments.add(s);
                    playlist.totalDurationSec += pendingDuration;
                    segmentIndex++;
                }
                pendingDuration = 0;
                pendingByteStart = -1;
                pendingByteLength = -1;
                continue;
            }

            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Map<String, String> a = attributes(after(line));
                HlsPlaylist.Rendition r = new HlsPlaylist.Rendition();
                // Average first, peak only as a fallback. BANDWIDTH is the peak a rendition ever
                // reaches; a file's size is its *average* rate times its length. Estimating from
                // the peak is why the ladder came out ragged — a 396p rung whose peak spikes
                // above a steady 480p was shown as the larger file. AVERAGE-BANDWIDTH is what the
                // size is actually made of, and it climbs with quality the way a size should.
                r.bandwidth = parseLong(a.get("AVERAGE-BANDWIDTH"), parseLong(a.get("BANDWIDTH"), 0));
                r.codecs = a.get("CODECS");
                String resolution = a.get("RESOLUTION");
                if (resolution != null) {
                    int x = resolution.toLowerCase(Locale.US).indexOf('x');
                    if (x > 0) {
                        r.width = (int) parseLong(resolution.substring(0, x), 0);
                        r.height = (int) parseLong(resolution.substring(x + 1), 0);
                    }
                }
                // Recorded, not resolved. The group it names may be declared further down the
                // playlist — the format does not require EXT-X-MEDIA to come first, and where it
                // did not, every rendition was given a null audio address and the download came
                // out silent. Matched up after the whole file has been read.
                r.audioGroup = a.get("AUDIO");
                pendingRendition = r;

            } else if (line.startsWith("#EXT-X-MEDIA:")) {
                Map<String, String> a = attributes(after(line));
                if ("AUDIO".equalsIgnoreCase(a.get("TYPE"))) {
                    String group = a.get("GROUP-ID");
                    String uri = a.get("URI");
                    if (group != null && !TextUtils.isEmpty(uri)) {
                        boolean isDefault = "YES".equalsIgnoreCase(a.get("DEFAULT"));
                        if (isDefault || !audioGroups.containsKey(group)) {
                            audioGroups.put(group, resolve(baseUrl, uri));
                        }
                    }
                }

            } else if (line.startsWith("#EXTINF:")) {
                String value = after(line);
                int comma = value.indexOf(',');
                if (comma >= 0) value = value.substring(0, comma);
                pendingDuration = parseDouble(value);

            } else if (line.startsWith("#EXT-X-BYTERANGE:")) {
                String value = after(line);
                int at = value.indexOf('@');
                if (at >= 0) {
                    pendingByteLength = parseLong(value.substring(0, at), -1);
                    pendingByteStart = parseLong(value.substring(at + 1), -1);
                } else {
                    pendingByteLength = parseLong(value, -1);
                }

            } else if (line.startsWith("#EXT-X-KEY:")) {
                Map<String, String> a = attributes(after(line));
                String method = a.get("METHOD");
                String keyFormat = a.get("KEYFORMAT");
                if (method == null || "NONE".equalsIgnoreCase(method)) {
                    currentKeyUri = null;
                    currentKeyIv = null;
                } else if ("AES-128".equalsIgnoreCase(method)
                        && (keyFormat == null || "identity".equalsIgnoreCase(keyFormat))) {
                    String uri = a.get("URI");
                    currentKeyUri = TextUtils.isEmpty(uri) ? null : resolve(baseUrl, uri);
                    currentKeyIv = a.get("IV");
                } else {
                    // SAMPLE-AES, or a KEYFORMAT naming a DRM system. Not ours to unwrap.
                    playlist.drmProtected = true;
                }

            } else if (line.startsWith("#EXT-X-MAP:")) {
                Map<String, String> a = attributes(after(line));
                String uri = a.get("URI");
                if (!TextUtils.isEmpty(uri)) playlist.initSegmentUrl = resolve(baseUrl, uri);
                String range = a.get("BYTERANGE");
                if (range != null) {
                    int at = range.indexOf('@');
                    if (at >= 0) {
                        playlist.initByteLength = parseLong(range.substring(0, at), -1);
                        playlist.initByteStart = parseLong(range.substring(at + 1), -1);
                    } else {
                        playlist.initByteLength = parseLong(range, -1);
                        playlist.initByteStart = 0;
                    }
                }

            } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                mediaSequence = parseLong(after(line), 0);

            } else if (line.startsWith("#EXT-X-PLAYLIST-TYPE:")) {
                vod = "VOD".equalsIgnoreCase(after(line));

            } else if (line.startsWith("#EXT-X-ENDLIST")) {
                endList = true;

            } else if (line.startsWith("#EXT-X-SESSION-KEY:")) {
                // An advertisement of a key system the player *may* use, not a statement that it
                // must. A master routinely lists several — an identity/AES-128 entry beside a
                // FairPlay or Widevine one — and the player takes whichever it supports.
                //
                // Treating any single DRM entry as proof the stream is protected marked streams
                // that also offered a plain AES-128 path, which is a path we can take: the card
                // came back "Protected", with its quality grid hidden, for a video that was
                // perfectly downloadable.
                Map<String, String> a = attributes(after(line));
                String method = a.get("METHOD");
                if (method == null || "NONE".equalsIgnoreCase(method)) continue;

                String keyFormat = a.get("KEYFORMAT");
                boolean open = "AES-128".equalsIgnoreCase(method)
                        && (keyFormat == null || "identity".equalsIgnoreCase(keyFormat));
                if (open) sessionKeyOpen = true;
                else sessionKeyDrm = true;
            }
        }

        // Protected only if every key system offered is one we cannot unwrap. Where an
        // identity/AES-128 entry sits beside them, that is the one we take.
        if (sessionKeyDrm && !sessionKeyOpen) playlist.drmProtected = true;

        // Now that every EXT-X-MEDIA has been seen, give each rendition the audio it named.
        for (HlsPlaylist.Rendition r : playlist.renditions) {
            if (r.audioGroup == null || r.audioUrl != null) continue;
            r.audioUrl = audioGroups.get(r.audioGroup);
        }

        if (!playlist.renditions.isEmpty()) playlist.master = true;
        playlist.live = !playlist.master && !endList && !vod;
        return playlist;
    }

    private static String after(String line) {
        int colon = line.indexOf(':');
        return colon < 0 ? "" : line.substring(colon + 1);
    }

    /**
     * Attribute lists allow quoted values containing commas, so a plain split on ',' is wrong.
     */
    static Map<String, String> attributes(String input) {
        Map<String, String> map = new LinkedHashMap<>();
        int i = 0;
        int n = input.length();
        while (i < n) {
            int eq = input.indexOf('=', i);
            if (eq < 0) break;
            String key = input.substring(i, eq).trim().toUpperCase(Locale.US);
            int j = eq + 1;
            String value;
            if (j < n && input.charAt(j) == '"') {
                int end = input.indexOf('"', j + 1);
                if (end < 0) end = n;
                value = input.substring(j + 1, end);
                i = Math.min(n, end + 1);
                if (i < n && input.charAt(i) == ',') i++;
            } else {
                int comma = input.indexOf(',', j);
                if (comma < 0) comma = n;
                value = input.substring(j, comma).trim();
                i = comma + 1;
            }
            if (!key.isEmpty()) map.put(key, value);
        }
        return map;
    }

    public static String resolve(String base, String reference) {
        if (TextUtils.isEmpty(reference)) return reference;
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference;
        try {
            return new URL(new URL(base), reference).toString();
        } catch (Exception e) {
            return reference;
        }
    }

    private static long parseLong(String s, long fallback) {
        if (s == null) return fallback;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double parseDouble(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
