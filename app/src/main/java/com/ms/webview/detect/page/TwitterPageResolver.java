package com.ms.webview.detect.page;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Http;
import com.ms.webview.detect.MediaRegistry;
import com.ms.webview.detect.extract.FoundMedia;
import com.ms.webview.detect.extract.JsonMediaWalker;
import com.ms.webview.detect.extract.TwitterExtractor;

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
 * X / Twitter, resolved from the public syndication endpoint that powers embedded tweets.
 *
 * <p>Overhearing X's own traffic does not work reliably. It issues one GraphQL request when a
 * post link is opened, before an injected hook can be listening, and the response is tied to a
 * session. The syndication endpoint has neither problem: no login, no cookies, and it returns
 * the same {@code video_info.variants} ladder, so the existing extractor reads it unchanged.
 */
public class TwitterPageResolver implements PageResolver {

    private static final Pattern STATUS = Pattern.compile(
            "(?:twitter|x)\\.com/[^/]+/status(?:es)?/(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean handles(String pageUrl) {
        return pageUrl != null && STATUS.matcher(pageUrl).find();
    }

    @Nullable
    @Override
    public String hintFor(String pageUrl) {
        String id = statusId(pageUrl);
        return id == null ? null : "tw:" + id;
    }

    @Nullable
    private static String statusId(String pageUrl) {
        if (pageUrl == null) return null;
        Matcher m = STATUS.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public List<FoundMedia> resolve(String pageUrl, String userAgent) throws Exception {
        String id = statusId(pageUrl);
        if (id == null) return Collections.emptyList();

        String json = fetchAny(id, userAgent);
        if (json == null) return Collections.emptyList();

        List<FoundMedia> found = JsonMediaWalker.walk(json, pageUrl,
                Collections.singletonList(new TwitterExtractor()));

        // Force the hint to the tweet id. It is what the page scanner derives from the address
        // bar, and the two must agree for a frame captured off the playing video to reach this
        // card. The syndication payload does not always carry an expanded_url to derive it from.
        List<FoundMedia> tagged = new ArrayList<>(found.size());
        for (FoundMedia media : found) {
            media.groupHint = "tw:" + id;
            tagged.add(media);
        }
        return tagged;
    }

    /** Tries the current endpoint, then progressively older shapes of it. */
    private String fetchAny(String id, String userAgent) {
        String[] urls = {
                "https://cdn.syndication.twimg.com/tweet-result?id=" + id
                        + "&token=" + token(id) + "&lang=en",
                "https://cdn.syndication.twimg.com/tweet-result?id=" + id + "&lang=en",
                "https://cdn.syndication.twimg.com/tweet?id=" + id + "&lang=en"
        };
        for (String url : urls) {
            String body = fetch(url, userAgent);
            if (body != null && body.contains("video_info")) return body;
        }
        return null;
    }

    private String fetch(String url, String userAgent) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", TextUtils.isEmpty(userAgent) ? Http.DEFAULT_UA : userAgent)
                .header("Referer", "https://platform.twitter.com/")
                .header("Accept", "application/json")
                .get()
                .build();
        try (Response response = Http.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(MediaRegistry.DIAG, "syndication HTTP " + response.code() + " " + url);
                return null;
            }
            ResponseBody body = response.body();
            return body == null ? null : body.string();
        } catch (IOException e) {
            Log.i(MediaRegistry.DIAG, "syndication failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * The endpoint expects the same token X's own embed widget derives from the tweet id.
     * Mirrors that calculation: {@code ((id / 1e15) * PI)} in base 36, with zeros and the
     * decimal point stripped. The callers above fall back to a tokenless request if it is
     * rejected.
     */
    static String token(String id) {
        try {
            double value = (Double.parseDouble(id) / 1e15) * Math.PI;
            return base36(value).replaceAll("(0+|\\.)", "");
        } catch (NumberFormatException e) {
            return "0";
        }
    }

    /** Java has no radix-36 formatting for doubles, so integer and fraction are done by hand. */
    private static String base36(double value) {
        double magnitude = Math.abs(value);
        long whole = (long) magnitude;
        double fraction = magnitude - whole;

        StringBuilder sb = new StringBuilder(Long.toString(whole, 36));
        if (fraction > 0) {
            sb.append('.');
            for (int i = 0; i < 16 && fraction > 0; i++) {
                fraction *= 36;
                int digit = (int) fraction;
                sb.append(Character.forDigit(digit, 36));
                fraction -= digit;
            }
        }
        return sb.toString();
    }
}
