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
 * A single Tumblr post, asked for directly rather than overheard.
 *
 * <p>Tumblr's own file for this platform, alongside {@code TumblrPolicy} and
 * {@code TumblrExtractor}. Nothing outside Tumblr reads any of the three, so a change made here
 * cannot reach another site.
 *
 * <p>What it adds over listening to the page: a post reached from a feed arrives as a panel over
 * that feed, and the payload describing it may have been fetched before the hook was installed
 * or folded into a response too large to carry. Asking for the post's own address gives its
 * video without depending on either.
 *
 * <p>Only ever a single post. A tagged or dashboard address names no post, and guessing at the
 * contents of a feed from outside is not something this can do.
 */
public class TumblrPageResolver implements PageResolver {

    /** tumblr.com/&lt;blog&gt;/&lt;id&gt;/&lt;slug&gt;, or the older blog.tumblr.com/post/&lt;id&gt;. */
    private static final Pattern POST_ADDRESS = Pattern.compile(
            "tumblr\\.com/(?:post/|[^/?#]+/)(\\d{9,})");

    /**
     * Tumblr serves its video from the media subdomains as a plain file. Stops at the first
     * quote, backslash or whitespace, because these addresses are found inside JSON embedded in
     * HTML, where a slash arrives escaped.
     */
    private static final Pattern MEDIA_URL = Pattern.compile(
            "https://[a-z0-9.-]*media\\.tumblr\\.com/[^\"'\\\\\\s<>]+?\\.(?:mp4|mov)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OG_VIDEO = Pattern.compile(
            "<meta[^>]+property=[\"']og:video[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_IMAGE = Pattern.compile(
            "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern OG_TITLE = Pattern.compile(
            "<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean handles(String pageUrl) {
        return pageUrl != null && POST_ADDRESS.matcher(pageUrl).find();
    }

    @Nullable
    @Override
    public String hintFor(String pageUrl) {
        String id = postId(pageUrl);
        return id == null ? null : "tmb:" + id;
    }

    @Nullable
    private static String postId(String pageUrl) {
        if (pageUrl == null) return null;
        Matcher m = POST_ADDRESS.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public List<FoundMedia> resolve(String pageUrl, String userAgent) throws Exception {
        String id = postId(pageUrl);
        if (id == null) return Collections.emptyList();

        String html = fetch(pageUrl, userAgent);
        if (html == null) return Collections.emptyList();

        // og:video is the post's own declaration of what it holds and is preferred over a
        // scrape. Falling back to the first media address in the document covers the posts that
        // do not carry the tag.
        String url = unescape(first(OG_VIDEO, html));
        if (!isMedia(url)) url = unescape(first(MEDIA_URL, html));
        if (!isMedia(url)) return Collections.emptyList();

        FoundMedia media = new FoundMedia(url);
        media.kind = MediaKind.PROGRESSIVE;
        media.thumbnail = unescape(first(OG_IMAGE, html));
        media.title = unescape(first(OG_TITLE, html));
        // The same name the extractor gives this post, so the two meet on one card rather than
        // building a second beside it.
        media.groupHint = "tmb:" + id;

        List<FoundMedia> found = new ArrayList<>(1);
        if (media.valid()) found.add(media);
        return found;
    }

    private static boolean isMedia(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String lower = url.toLowerCase(java.util.Locale.US);
        return lower.startsWith("http") && (lower.contains(".mp4") || lower.contains(".mov"));
    }

    @Nullable
    private String fetch(String url, String userAgent) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", TextUtils.isEmpty(userAgent) ? Http.DEFAULT_UA : userAgent)
                .header("Accept", "text/html")
                .get()
                .build();
        try (Response response = Http.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(MediaRegistry.DIAG, "tumblr post HTTP " + response.code());
                return null;
            }
            ResponseBody body = response.body();
            return body == null ? null : body.string();
        } catch (IOException e) {
            Log.i(MediaRegistry.DIAG, "tumblr post fetch failed: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static String first(Pattern pattern, String input) {
        Matcher m = pattern.matcher(input);
        return m.find() ? (m.groupCount() >= 1 ? m.group(1) : m.group()) : null;
    }

    /** These arrive inside JSON embedded in HTML, so slashes and ampersands come escaped. */
    @Nullable
    private static String unescape(@Nullable String value) {
        if (value == null) return null;
        return value.replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")
                .replace("\\\"", "\"")
                .trim();
    }
}
