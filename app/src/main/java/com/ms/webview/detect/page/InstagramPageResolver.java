package com.ms.webview.detect.page;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Http;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.MediaRegistry;
import com.ms.webview.detect.extract.FoundMedia;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Instagram posts and reels, resolved from the public embed page.
 *
 * <p>Signed out, instagram.com renders the reel but never issues the API call the extractors
 * listen for — the page arrives complete, so there is nothing to overhear. The embed endpoint
 * that powers third-party post embeds needs no session and carries the media URL in its HTML,
 * which is what lets a logged-out reel be detected at all.
 */
public class InstagramPageResolver implements PageResolver {

    private static final Pattern SHORTCODE = Pattern.compile(
            "instagram\\.com/(?:[^/]+/)?(?:p|reels?|tv)/([A-Za-z0-9_-]+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern VIDEO_URL = Pattern.compile("\"video_url\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern POSTER = Pattern.compile(
            "\"(?:display_url|thumbnail_src)\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DIMENSIONS = Pattern.compile(
            "\"dimensions\"\\s*:\\s*\\{\\s*\"height\"\\s*:\\s*(\\d+)\\s*,\\s*\"width\"\\s*:\\s*(\\d+)");
    private static final Pattern CAPTION = Pattern.compile("\"edge_media_to_caption\".{0,200}?"
            + "\"text\"\\s*:\\s*\"([^\"]{1,160})\"", Pattern.DOTALL);
    private static final Pattern OWNER = Pattern.compile("\"username\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    public boolean handles(String pageUrl) {
        return pageUrl != null && SHORTCODE.matcher(pageUrl).find();
    }

    @Nullable
    @Override
    public String hintFor(String pageUrl) {
        String code = shortcode(pageUrl);
        return code == null ? null : "ig:" + code;
    }

    @Nullable
    private static String shortcode(String pageUrl) {
        if (pageUrl == null) return null;
        Matcher m = SHORTCODE.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public List<FoundMedia> resolve(String pageUrl, String userAgent) throws Exception {
        String code = shortcode(pageUrl);
        if (code == null) return Collections.emptyList();

        String html = fetch("https://www.instagram.com/p/" + code + "/embed/captioned/", userAgent);
        if (html == null) {
            html = fetch("https://www.instagram.com/reel/" + code + "/embed/", userAgent);
        }
        if (html == null) return Collections.emptyList();

        String url = unescape(first(VIDEO_URL, html));
        if (TextUtils.isEmpty(url)) return Collections.emptyList();

        FoundMedia media = new FoundMedia(url);
        media.kind = url.contains(".m3u8") ? MediaKind.HLS : MediaKind.PROGRESSIVE;
        media.thumbnail = unescape(first(POSTER, html));
        media.title = unescape(first(CAPTION, html));
        media.author = first(OWNER, html);
        // The scanner derives the same hint from the address bar, so a frame captured off the
        // playing element lands on this card.
        media.groupHint = "ig:" + code;

        Matcher dims = DIMENSIONS.matcher(html);
        if (dims.find()) {
            try {
                media.height = Integer.parseInt(dims.group(1));
                media.width = Integer.parseInt(dims.group(2));
            } catch (NumberFormatException ignored) {
            }
        }

        List<FoundMedia> found = new ArrayList<>(1);
        if (media.valid()) found.add(media);
        return found;
    }

    private String fetch(String url, String userAgent) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", TextUtils.isEmpty(userAgent) ? Http.DEFAULT_UA : userAgent)
                .header("Accept", "text/html")
                .get()
                .build();
        try (Response response = Http.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(MediaRegistry.DIAG, "embed HTTP " + response.code() + " " + url);
                return null;
            }
            ResponseBody body = response.body();
            String html = body == null ? null : body.string();
            return html != null && html.contains("video_url") ? html : null;
        } catch (IOException e) {
            Log.i(MediaRegistry.DIAG, "embed fetch failed: " + e.getMessage());
            return null;
        }
    }

    private static String first(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? m.group(1) : null;
    }

    /** The URLs are embedded in JSON inside HTML, so slashes and ampersands arrive escaped. */
    private static String unescape(String value) {
        if (value == null) return null;
        return value.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\\"", "\"")
                .replace("\\n", " ")
                .trim();
    }
}
