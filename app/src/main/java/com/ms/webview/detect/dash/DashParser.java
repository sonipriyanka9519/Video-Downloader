package com.ms.webview.detect.dash;

import android.text.TextUtils;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an MPD into its list of renditions.
 *
 * <p>Scoped to the shape Instagram and Facebook actually publish: every Representation carries a
 * {@code <BaseURL>} pointing at a complete, separately-encoded file — video-only and audio-only
 * side by side. That makes each video Representation a real quality the user can pick, and the
 * download a fetch of two files plus a mux, with no segment assembly involved.
 *
 * <p>Manifests built from SegmentTemplate are recognised and flagged rather than mis-parsed.
 */
public final class DashParser {

    private static final Pattern DURATION = Pattern.compile(
            "P(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)D)?"
                    + "(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:([\\d.]+)S)?)?");

    private DashParser() {
    }

    public static DashManifest parse(String xml, String baseUrl) {
        DashManifest manifest = new DashManifest();
        if (TextUtils.isEmpty(xml)) return manifest;

        try {
            XmlPullParser parser = Xml.newPullParser();
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            parser.setInput(new StringReader(xml));

            String manifestBase = baseUrl;
            // Attributes inherit down the tree, so the enclosing AdaptationSet's values stand in
            // whenever a Representation does not restate them.
            String setMime = null;
            String setCodecs = null;
            int setWidth = 0;
            int setHeight = 0;

            DashManifest.Representation current = null;
            StringBuilder text = null;

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    String name = parser.getName();

                    if ("MPD".equalsIgnoreCase(name)) {
                        manifest.durationMs = parseDuration(
                                parser.getAttributeValue(null, "mediaPresentationDuration"));

                    } else if ("ContentProtection".equalsIgnoreCase(name)) {
                        manifest.drmProtected = true;

                    } else if ("SegmentTemplate".equalsIgnoreCase(name)
                            || "SegmentList".equalsIgnoreCase(name)) {
                        manifest.templated = true;

                    } else if ("AdaptationSet".equalsIgnoreCase(name)) {
                        setMime = first(parser.getAttributeValue(null, "mimeType"),
                                parser.getAttributeValue(null, "contentType"));
                        setCodecs = parser.getAttributeValue(null, "codecs");
                        setWidth = intOf(parser.getAttributeValue(null, "width"));
                        setHeight = intOf(parser.getAttributeValue(null, "height"));

                    } else if ("Representation".equalsIgnoreCase(name)) {
                        current = new DashManifest.Representation();
                        current.id = parser.getAttributeValue(null, "id");
                        current.mimeType = first(parser.getAttributeValue(null, "mimeType"), setMime);
                        current.codecs = first(parser.getAttributeValue(null, "codecs"), setCodecs);
                        current.width = orElse(intOf(parser.getAttributeValue(null, "width")), setWidth);
                        current.height = orElse(intOf(parser.getAttributeValue(null, "height")), setHeight);
                        current.bandwidth = longOf(parser.getAttributeValue(null, "bandwidth"));
                        current.video = isVideo(current);

                    } else if ("BaseURL".equalsIgnoreCase(name)) {
                        text = new StringBuilder();
                    }

                } else if (event == XmlPullParser.TEXT) {
                    if (text != null) text.append(parser.getText());

                } else if (event == XmlPullParser.END_TAG) {
                    String name = parser.getName();

                    if ("BaseURL".equalsIgnoreCase(name) && text != null) {
                        String value = text.toString().trim();
                        text = null;
                        if (!value.isEmpty()) {
                            if (current != null) current.url = resolve(manifestBase, value);
                            else manifestBase = resolve(manifestBase, value);
                        }

                    } else if ("Representation".equalsIgnoreCase(name)) {
                        if (current != null && !TextUtils.isEmpty(current.url)) {
                            if (current.video) manifest.videos.add(current);
                            else manifest.audios.add(current);
                        }
                        current = null;

                    } else if ("AdaptationSet".equalsIgnoreCase(name)) {
                        setMime = null;
                        setCodecs = null;
                        setWidth = 0;
                        setHeight = 0;
                    }
                }
                event = parser.next();
            }
        } catch (Exception e) {
            // A partial parse is still worth returning: half a ladder beats none.
        }
        return manifest;
    }

    private static boolean isVideo(DashManifest.Representation r) {
        if (r.width > 0 || r.height > 0) return true;
        String mime = r.mimeType == null ? "" : r.mimeType.toLowerCase(Locale.US);
        if (mime.contains("video")) return true;
        if (mime.contains("audio")) return false;
        String codecs = r.codecs == null ? "" : r.codecs.toLowerCase(Locale.US);
        return codecs.startsWith("avc") || codecs.startsWith("hev") || codecs.startsWith("hvc")
                || codecs.startsWith("vp9") || codecs.startsWith("vp0") || codecs.startsWith("av0");
    }

    static long parseDuration(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        Matcher m = DURATION.matcher(value.trim());
        if (!m.matches()) return 0;
        double seconds = 0;
        seconds += group(m, 3) * 86400;   // days
        seconds += group(m, 4) * 3600;
        seconds += group(m, 5) * 60;
        seconds += group(m, 6);
        return (long) (seconds * 1000);
    }

    private static double group(Matcher m, int index) {
        String value = m.group(index);
        if (TextUtils.isEmpty(value)) return 0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String resolve(String base, String reference) {
        if (TextUtils.isEmpty(reference)) return reference;
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference;
        if (TextUtils.isEmpty(base)) return reference;
        try {
            return new URL(new URL(base), reference).toString();
        } catch (Exception e) {
            return reference;
        }
    }

    private static String first(String a, String b) {
        return TextUtils.isEmpty(a) ? b : a;
    }

    private static int orElse(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private static int intOf(String value) {
        return (int) longOf(value);
    }

    private static long longOf(String value) {
        if (TextUtils.isEmpty(value)) return 0;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
