package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Null-tolerant accessors. Platform JSON is deeply inconsistent about types and presence. */
public final class Json {

    private Json() {
    }

    public static String str(JsonObject o, String... keys) {
        for (String key : keys) {
            JsonElement e = o == null ? null : o.get(key);
            if (e != null && e.isJsonPrimitive()) {
                String s = e.getAsString();
                if (s != null && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    public static long num(JsonObject o, String... keys) {
        for (String key : keys) {
            JsonElement e = o == null ? null : o.get(key);
            if (e != null && e.isJsonPrimitive()) {
                try {
                    return (long) e.getAsDouble();
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    /** Seconds as an int or a float, normalised to milliseconds. */
    public static long seconds(JsonObject o, String... keys) {
        for (String key : keys) {
            JsonElement e = o == null ? null : o.get(key);
            if (e != null && e.isJsonPrimitive()) {
                try {
                    return (long) (e.getAsDouble() * 1000);
                } catch (Exception ignored) {
                }
            }
        }
        return 0;
    }

    public static JsonObject obj(JsonObject o, String... keys) {
        for (String key : keys) {
            JsonElement e = o == null ? null : o.get(key);
            if (e != null && e.isJsonObject()) return e.getAsJsonObject();
        }
        return null;
    }

    public static JsonArray arr(JsonObject o, String... keys) {
        for (String key : keys) {
            JsonElement e = o == null ? null : o.get(key);
            if (e != null && e.isJsonArray()) return e.getAsJsonArray();
        }
        return null;
    }

    /** First non-empty string in an array, or null. TikTok returns every URL as a list. */
    public static String firstString(JsonArray a) {
        if (a == null) return null;
        for (JsonElement e : a) {
            if (e.isJsonPrimitive()) {
                String s = e.getAsString();
                if (s != null && !s.isEmpty()) return s;
            }
        }
        return null;
    }

    /**
     * Reads a URL that may be stored as a plain string, or wrapped in an object holding a
     * {@code url} or a {@code url_list} array. TikTok uses the wrapped form throughout, and
     * spells its keys differently between the app and web payloads.
     */
    public static String urlFrom(JsonObject parent, String... keys) {
        if (parent == null) return null;
        for (String key : keys) {
            JsonElement e = parent.get(key);
            if (e == null || e.isJsonNull()) continue;

            if (e.isJsonPrimitive()) {
                String s = e.getAsString();
                if (s != null && s.startsWith("http")) return s;
            } else if (e.isJsonObject()) {
                JsonObject o = e.getAsJsonObject();
                String direct = str(o, "url", "Url", "URI", "uri");
                if (direct != null) return direct;
                String listed = firstString(arr(o, "url_list", "UrlList", "urlList"));
                if (listed != null) return listed;
            } else if (e.isJsonArray()) {
                String listed = firstString(e.getAsJsonArray());
                if (listed != null) return listed;
                // Kwai and Likee list CDN mirrors as objects: [{"cdn":..,"url":..}, ...]
                JsonObject entry = first(e.getAsJsonArray());
                String nested = str(entry, "url", "Url", "src");
                if (nested != null) return nested;
            }
        }
        return null;
    }

    /** Largest value in a {@code {"720": url, "1080": url}} style map. */
    public static String largestKeyedUrl(JsonObject map) {
        if (map == null) return null;
        String best = null;
        long bestKey = Long.MIN_VALUE;
        for (String key : map.keySet()) {
            JsonElement e = map.get(key);
            if (e == null || !e.isJsonPrimitive()) continue;
            String value = e.getAsString();
            if (value == null || !value.startsWith("http")) continue;
            long numeric;
            try {
                numeric = Long.parseLong(key.replaceAll("\\D", ""));
            } catch (NumberFormatException ex) {
                numeric = 0;
            }
            if (best == null || numeric > bestKey) {
                best = value;
                bestKey = numeric;
            }
        }
        return best;
    }

    /** First object element of an array, or null. */
    public static JsonObject first(JsonArray a) {
        if (a == null) return null;
        for (JsonElement e : a) {
            if (e.isJsonObject()) return e.getAsJsonObject();
        }
        return null;
    }

    public static boolean looksLikeMediaUrl(String url) {
        if (url == null || !url.startsWith("http")) return false;
        String lower = url.toLowerCase(java.util.Locale.US);

        // Images turn up under the same generic key names as video ("src", "source", "image"),
        // so they have to be excluded explicitly rather than merely not matched.
        if (lower.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic|svg|ico)(\\?|#|$).*")) return false;

        return lower.contains(".mp4") || lower.contains(".m3u8") || lower.contains(".webm")
                || lower.contains(".mpd") || lower.contains(".m4v") || lower.contains(".mov");
    }
}
