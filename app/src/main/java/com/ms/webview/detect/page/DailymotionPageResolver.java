package com.ms.webview.detect.page;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ms.webview.core.Http;
import com.ms.webview.detect.MediaRegistry;
import com.ms.webview.detect.extract.DailymotionExtractor;
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
 * Dailymotion, asked directly rather than overheard.
 *
 * <p>Waiting for the page to reveal its own media does not work here. Dailymotion runs a
 * preroll from an ad server before it touches the real video, so the manifest arrives late if
 * it arrives at all — and in the meantime the only things the detector sees are the ad script
 * and the page's own address. That is the whole of the delay, and no amount of listening
 * shortens it.
 *
 * <p>The player metadata endpoint needs no session and states everything at once: title,
 * poster, duration, and the {@code qualities} ladder. It is fetched the moment a video address
 * is recognised, so the sheet is ready before playback starts.
 *
 * <p>Parsing is deliberately left to {@link DailymotionExtractor} — this endpoint returns the
 * same document the player fetches, so giving it a second reader would mean two places to fix
 * when Dailymotion reshuffles it.
 */
public class DailymotionPageResolver implements PageResolver {

    /** dailymotion.com/video/x9hrf9g, /embed/video/x9hrf9g, dai.ly/x9hrf9g. */
    private static final Pattern VIDEO_ID = Pattern.compile(
            "(?:dailymotion\\.com/(?:embed/)?video/|dai\\.ly/)([A-Za-z0-9]+)",
            Pattern.CASE_INSENSITIVE);

    private static final String METADATA = "https://www.dailymotion.com/player/metadata/video/";

    @Override
    public boolean handles(String pageUrl) {
        return pageUrl != null && VIDEO_ID.matcher(pageUrl).find();
    }

    @Nullable
    @Override
    public String hintFor(String pageUrl) {
        String id = videoId(pageUrl);
        return id == null ? null : "dm:" + id;
    }

    @Nullable
    private static String videoId(String pageUrl) {
        if (pageUrl == null) return null;
        Matcher m = VIDEO_ID.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }

    @Override
    public List<FoundMedia> resolve(String pageUrl, String userAgent) throws Exception {
        String id = videoId(pageUrl);
        if (id == null) return Collections.emptyList();

        String json = fetch(METADATA + id, pageUrl, userAgent);
        if (json == null) return Collections.emptyList();

        JsonObject node = JsonParser.parseString(json).getAsJsonObject();
        List<FoundMedia> found = new ArrayList<>(4);
        new DailymotionExtractor().inspect(node, pageUrl, found);
        return found;
    }

    /**
     * Dailymotion checks where the request claims to come from, so the page is sent as the
     * referer rather than only its origin.
     */
    @Nullable
    private String fetch(String url, String pageUrl, String userAgent) {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", TextUtils.isEmpty(userAgent) ? Http.DEFAULT_UA : userAgent)
                .header("Accept", "application/json")
                .header("Referer", pageUrl)
                .get()
                .build();
        try (Response response = Http.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(MediaRegistry.DIAG, "dailymotion metadata HTTP " + response.code());
                return null;
            }
            ResponseBody body = response.body();
            String text = body == null ? null : body.string();
            // An error document comes back as valid JSON with no ladder in it; treating that as
            // a miss keeps the caller from parsing something that cannot hold media.
            return text != null && text.contains("qualities") ? text : null;
        } catch (IOException e) {
            Log.i(MediaRegistry.DIAG, "dailymotion metadata failed: " + e.getMessage());
            return null;
        }
    }
}
