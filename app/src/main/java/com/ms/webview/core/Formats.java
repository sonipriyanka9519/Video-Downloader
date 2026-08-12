package com.ms.webview.core;

import android.net.Uri;
import android.text.TextUtils;

import java.util.Locale;
import java.util.regex.Pattern;

public final class Formats {

    private Formats() {
    }

    public static String bytes(long b) {
        if (b <= 0) return "—";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.0f KB", b / 1024f);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024f * 1024f));
        return String.format(Locale.US, "%.2f GB", b / (1024f * 1024f * 1024f));
    }

    public static String speed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) return "";
        return bytes(bytesPerSecond) + "/s";
    }

    public static String duration(long ms) {
        if (ms <= 0) return "";
        long total = ms / 1000;
        long h = total / 3600, m = (total % 3600) / 60, s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    /** The rungs encoders actually target. Odd sizes near one of these are that one. */
    private static final int[] RUNGS = {144, 240, 360, 480, 540, 576, 720, 1080, 1440, 2160};

    /**
     * The number a quality is known by: the shorter side, not the height.
     *
     * <p>A portrait reel encoded 720x1274 is 720p — everyone labels video by its short edge.
     * Reading the height instead produced "1274p" and "1254p" for two encodes of the same rung,
     * which then looked like a bug when the larger number carried the smaller file.
     */
    public static int qualityRung(int width, int height) {
        int shorter = (width > 0 && height > 0) ? Math.min(width, height) : Math.max(width, height);
        if (shorter <= 0) return 0;
        return snapToRung(shorter);
    }

    /** Pulls 718 or 722 onto 720; leaves genuinely unusual sizes alone. */
    private static int snapToRung(int value) {
        for (int rung : RUNGS) {
            if (Math.abs(value - rung) <= Math.max(8, Math.round(rung * 0.05f))) return rung;
        }
        return value;
    }

    /** "1080p", or empty when the dimensions are unknown. */
    public static String quality(int width, int height) {
        int rung = qualityRung(width, height);
        return rung > 0 ? rung + "p" : "";
    }

    /**
     * Names a URL's last path segment carries that say nothing about the video. Streaming
     * players all reach for the same handful, so "manifest.m3u8" and "index.mp4" turn up
     * constantly as both the card title and the saved file name.
     */
    private static final Pattern GENERIC_NAME = Pattern.compile(
            "^(manifest|master|playlist|index|video|media|stream|chunklist|prog_index|init)"
                    + "([_-]?\\d+)?(\\.[a-z0-9]{2,5})?$",
            Pattern.CASE_INSENSITIVE);

    /** Whether a path segment is worth showing to a person. */
    public static boolean isMeaningfulName(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String trimmed = name.trim();
        if (GENERIC_NAME.matcher(trimmed).matches()) return false;
        if (trimmed.contains(" ")) return true;
        if (trimmed.length() < 24) return true;
        return !looksLikeToken(trimmed);
    }

    /**
     * An opaque CDN handle rather than a name.
     *
     * <p>A hyphen used to be taken as proof of a readable slug, which is how Facebook addresses
     * ended up as video titles: {@code AQOtJQ7QVzYrH8lplTT424okfn0cWh-69GnDl…} has hyphens in
     * it and sailed straight through. Both slugs and base64 handles use hyphens and
     * underscores, so the separator says nothing. What separates them is the mix — a handle
     * runs upper case, lower case and digits together for a dozen characters at a stretch,
     * where a slug is words, and words are short and rarely change case mid-run.
     */
    private static boolean looksLikeToken(String name) {
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        int run = 0;
        int longestRun = 0;

        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '-' || c == '_' || c == '.') {
                run = 0;
                continue;
            }
            if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
            else if (Character.isDigit(c)) digit = true;
            longestRun = Math.max(longestRun, ++run);
        }
        return upper && lower && digit && longestRun >= 12;
    }

    /** A safe, reasonably descriptive file name for a saved video. */
    public static String fileName(String title, String url, String extension) {
        String base = title;
        if (TextUtils.isEmpty(base) || !isMeaningfulName(base)) {
            String segment = lastPathSegment(url);
            base = isMeaningfulName(segment) ? segment : hostOf(url);
        }
        if (TextUtils.isEmpty(base)) base = "video";
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        base = shorten(base, 60);
        // Strip any extension already present so we do not end up with "clip.mp4.mp4".
        int dot = base.lastIndexOf('.');
        if (dot > 0 && base.length() - dot <= 5) base = base.substring(0, dot);
        // A name made entirely of separators, or emptied by the trim, is no name at all.
        base = base.replaceAll("^[._\\s]+|[._\\s]+$", "");
        if (base.isEmpty()) base = "video";
        return base + "_" + (System.currentTimeMillis() % 100000) + "." + extension;
    }

    /**
     * Cuts a name to length without splitting a character in half.
     *
     * <p>A plain {@code substring} counts UTF-16 units, and an emoji is two of them. Cutting
     * between the pair leaves a lone surrogate — a character that is not a character — and a file
     * name carrying one is rejected or mangled by the media store rather than saved. Captions on
     * a social feed are full of emoji, so the 60th unit lands inside one often enough to matter.
     */
    private static String shorten(String name, int max) {
        if (name.length() <= max) return name;
        // The unit at max is being dropped. If the one before it is the first half of a pair,
        // its partner is the one going, so it has to go too.
        int end = Character.isHighSurrogate(name.charAt(max - 1)) ? max - 1 : max;
        return name.substring(0, end).trim();
    }

    public static String lastPathSegment(String url) {
        try {
            String seg = Uri.parse(url).getLastPathSegment();
            return seg == null ? "" : seg;
        } catch (Exception e) {
            return "";
        }
    }

    public static String hostOf(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h == null ? "" : h;
        } catch (Exception e) {
            return "";
        }
    }
}
