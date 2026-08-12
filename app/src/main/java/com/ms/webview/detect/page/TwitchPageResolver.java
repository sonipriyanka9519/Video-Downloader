package com.ms.webview.detect.page;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ms.webview.core.Http;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.MediaRegistry;
import com.ms.webview.detect.extract.FoundMedia;
import com.ms.webview.detect.extract.Json;
import com.ms.webview.detect.extract.JsonMediaWalker;
import com.ms.webview.detect.extract.TwitchExtractor;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Twitch, asked for directly rather than overheard.
 *
 * <p>Opening a Twitch link fires its GraphQL request as the page loads, before an injected hook
 * can be listening, so there is nothing to intercept — the same reason X and Instagram needed
 * resolvers. Querying the endpoint ourselves makes a video detectable however it was reached.
 *
 * <p>Handles both shapes. A <b>clip</b> exposes a ladder of complete files. A <b>VOD</b> does
 * not: it is served as an HLS playlist that only exists once a signed token is exchanged for
 * it, so the token is fetched and the playlist address assembled — and the usual HLS path takes
 * it from there, including its own quality ladder.
 *
 * <p>Live channels remain out of reach, and always will be: a stream has no end to download to.
 */
public class TwitchPageResolver implements PageResolver {

    /** twitch.tv/channel/clip/Slug and clips.twitch.tv/Slug. */
    private static final Pattern CLIP = Pattern.compile(
            "(?:clips\\.twitch\\.tv/|twitch\\.tv/(?:[^/]+/)?clip/)([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);
    /** twitch.tv/videos/123456 and the older channel/video/123456 form. */
    private static final Pattern VOD = Pattern.compile(
            "twitch\\.tv/(?:[^/]+/)?videos?/(\\d+)", Pattern.CASE_INSENSITIVE);

    /**
     * The public client id twitch.tv's own web player ships with. Anonymous, unauthenticated,
     * and the only thing the endpoint asks for.
     */
    private static final String WEB_CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final String PLAYER_PARAMS =
            "params:{platform:\\\"web\\\",playerBackend:\\\"mediaplayer\\\",playerType:\\\"site\\\"}";
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    @Override
    public boolean handles(String pageUrl) {
        return pageUrl != null
                && (CLIP.matcher(pageUrl).find() || VOD.matcher(pageUrl).find());
    }

    @Nullable
    @Override
    public String hintFor(String pageUrl) {
        String slug = match(CLIP, pageUrl);
        if (slug != null) return "twitch:" + slug;
        String vod = match(VOD, pageUrl);
        return vod == null ? null : "twitch:v" + vod;
    }

    @Override
    public List<FoundMedia> resolve(String pageUrl, String userAgent) throws Exception {
        String slug = match(CLIP, pageUrl);
        if (slug != null) return resolveClip(slug, userAgent);

        String vod = match(VOD, pageUrl);
        if (vod != null) return resolveVod(vod, userAgent);

        return Collections.emptyList();
    }

    // ---------------------------------------------------------------------- clips

    private List<FoundMedia> resolveClip(String slug, String userAgent) {
        // playbackAccessToken is requested alongside the ladder because a sourceURL is refused
        // without the signature and token it carries.
        String body = "{\"query\":\"query{clip(slug:\\\"" + slug + "\\\"){"
                + "id title durationSeconds thumbnailURL "
                + "videoQualities{quality frameRate sourceURL} "
                + "playbackAccessToken(" + PLAYER_PARAMS + "){signature value} "
                + "broadcaster{displayName}}}\"}";

        String json = post(body, userAgent);
        if (json == null || !json.contains("videoQualities")) return Collections.emptyList();

        List<FoundMedia> found = JsonMediaWalker.walk(json, "https://www.twitch.tv/",
                Collections.singletonList(new TwitchExtractor()));

        List<FoundMedia> tagged = new ArrayList<>(found.size());
        for (FoundMedia media : found) {
            media.groupHint = "twitch:" + slug;
            tagged.add(media);
        }
        return tagged;
    }

    // ----------------------------------------------------------------------- VODs

    private List<FoundMedia> resolveVod(String id, String userAgent) throws IOException {
        String body = "{\"query\":\"query{"
                + "video(id:\\\"" + id + "\\\"){title lengthSeconds "
                + "previewThumbnailURL(height:180,width:320) owner{displayName}} "
                + "videoPlaybackAccessToken(id:\\\"" + id + "\\\"," + PLAYER_PARAMS + ")"
                + "{signature value}}\"}";

        String json = post(body, userAgent);
        if (json == null) return Collections.emptyList();

        JsonObject data = dataOf(json);
        if (data == null) return Collections.emptyList();

        JsonObject token = Json.obj(data, "videoPlaybackAccessToken");
        String signature = Json.str(token, "signature");
        String value = Json.str(token, "value");
        if (signature == null || value == null) {
            Log.i(MediaRegistry.DIAG, "twitch vod token missing for " + id);
            return Collections.emptyList();
        }

        FoundMedia media = new FoundMedia(usherUrl(id, signature, value));
        // The playlist is a master: the HLS resolver expands it into the quality ladder.
        media.kind = MediaKind.HLS;
        media.groupHint = "twitch:v" + id;

        JsonObject video = Json.obj(data, "video");
        if (video != null) {
            media.title = Json.str(video, "title");
            media.thumbnail = Json.str(video, "previewThumbnailURL");
            media.durationMs = Json.seconds(video, "lengthSeconds");
            media.author = Json.str(Json.obj(video, "owner"), "displayName");
        }

        return media.valid() ? Collections.singletonList(media) : Collections.emptyList();
    }

    /** The address the web player builds once it holds a token. */
    private String usherUrl(String id, String signature, String value) throws IOException {
        return "https://usher.ttvnw.net/vod/" + id + ".m3u8"
                + "?allow_source=true"
                + "&allow_audio_only=true"
                + "&player=twitchweb"
                + "&playlist_include_framerate=true"
                + "&sig=" + signature
                + "&token=" + URLEncoder.encode(value, "UTF-8")
                + "&p=" + new Random().nextInt(9_999_999);
    }

    // ------------------------------------------------------------------- plumbing

    /**
     * A plain query rather than one of Twitch's persisted hashes. The hashes rotate and would
     * need chasing; the schema behind them does not.
     */
    @Nullable
    private String post(String body, String userAgent) {
        Request request = new Request.Builder()
                .url("https://gql.twitch.tv/gql")
                .header("Client-ID", WEB_CLIENT_ID)
                .header("User-Agent", TextUtils.isEmpty(userAgent) ? Http.DEFAULT_UA : userAgent)
                .header("Origin", "https://www.twitch.tv")
                .header("Referer", "https://www.twitch.tv/")
                .post(RequestBody.create(body, JSON_TYPE))
                .build();

        try (Response response = Http.client().newCall(request).execute()) {
            if (!response.isSuccessful()) {
                Log.i(MediaRegistry.DIAG, "twitch gql HTTP " + response.code());
                return null;
            }
            ResponseBody payload = response.body();
            return payload == null ? null : payload.string();
        } catch (IOException e) {
            Log.i(MediaRegistry.DIAG, "twitch gql failed: " + e.getMessage());
            return null;
        }
    }

    @Nullable
    private static JsonObject dataOf(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            return root.getAsJsonObject("data");
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable
    private static String match(Pattern pattern, String pageUrl) {
        if (pageUrl == null) return null;
        Matcher m = pattern.matcher(pageUrl);
        return m.find() ? m.group(1) : null;
    }
}
