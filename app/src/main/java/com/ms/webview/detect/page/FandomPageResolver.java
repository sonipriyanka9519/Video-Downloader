package com.ms.webview.detect.page;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Http;
import com.ms.webview.detect.MediaRegistry;
import com.ms.webview.detect.extract.FandomExtractor;
import com.ms.webview.detect.extract.FoundMedia;
import com.ms.webview.detect.extract.JsonMediaWalker;
import com.ms.webview.detect.extract.SiteExtractor;
import com.ms.webview.detect.site.FandomPolicy;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fandom / Wikia, asked for directly rather than overheard.
 *
 * <p>Fandom's own file, alongside {@code FandomPolicy} and {@code FandomExtractor}. Nothing
 * outside Fandom reads any of the three.
 *
 * <p>The same problem Twitch has, and the same answer. Fandom's player fetches its payload as
 * the page comes up, and on a hub like {@code /video} that request is made and answered before
 * an injected hook is listening — so there is nothing to intercept and the page reads as having
 * no video on it at all, however plainly one is playing.
 *
 * <p>Fandom plays through JW Player, whose catalogue is public: given the media id, the whole
 * record — every rendition, the poster, the running time — comes back from one address with no
 * key and no session. The id is in the page, so the page is read for it and the record fetched.
 *
 * <p>Parsing is left to {@link FandomExtractor}: this is the same document the player receives,
 * so giving it a second reader would mean two places to fix when the shape changes.
 */
public class FandomPageResolver implements PageResolver {

    /** JW Player's public catalogue. No key, no session — the id is the whole request. */
    private static final String MEDIA_ENDPOINT = "https://cdn.jwplayer.com/v2/media/";

    /**
     * The media id, in the spellings Fandom's pages use for it. JW ids are short alphanumeric
     * tokens, so the length is bounded to keep this from matching arbitrary words.
     */
    private static final Pattern MEDIA_ID = Pattern.compile(
            "(?:\"(?:mediaId|mediaid|jwMediaId|videoId)\"\\s*:\\s*\"([A-Za-z0-9]{6,12})\""
                    + "|cdn\\.jwplayer\\.com/(?:v2/)?(?:media|manifests|videos)/([A-Za-z0-9]{6,12})"
                    + "|content\\.jwplatform\\.com/(?:manifests|videos)/([A-Za-z0-9]{6,12}))");

    @Override
    public boolean handles(String pageUrl) {
        if (pageUrl == null) return false;
        String lower = pageUrl.toLowerCase(java.util.Locale.US);
        return lower.contains("fandom.com") || lower.contains("wikia.com")
                || lower.contains("wikia.org");
    }

    /**
     * The clip named in the address, where there is one. A wiki article names none, and there is
     * nothing to compare against until the page has been read.
     */
    @Nullable
    @Override
    public String hintFor(String pageUrl) {
        String id = FandomPolicy.mediaIdIn(pageUrl);
        return id == null ? null : "fandom:" + id;
    }

    @Override
    public List<FoundMedia> resolve(String pageUrl, String userAgent) throws Exception {
        String mediaId = mediaIdFor(pageUrl, userAgent);
        if (mediaId == null) {
            Log.i(MediaRegistry.DIAG, "fandom: no media id in page");
            return Collections.emptyList();
        }

        String json = fetch(MEDIA_ENDPOINT + mediaId, pageUrl, userAgent, "application/json");
        if (json == null) return Collections.emptyList();

        List<SiteExtractor> readers = Collections.singletonList(new FandomExtractor());
        return JsonMediaWalker.walk(json, pageUrl, readers);
    }

    /**
     * Which clip to fetch: the one the address names, or failing that the first the page
     * mentions.
     *
     * <p>The address is asked first and, when it answers, the page is not fetched at all. Two
     * things come of that. It is a whole round trip faster, on a detection the viewer is waiting
     * for. And it is right, where reading the page is only probably right: a hub page lists the
     * rail of clips to watch next, so "the first id mentioned" is as likely to be a suggestion as
     * the video playing — which is how a page showing one video came to offer two, neither of
     * them reliably the one on screen.
     */
    @Nullable
    private String mediaIdFor(String pageUrl, String userAgent) {
        String fromAddress = FandomPolicy.mediaIdIn(pageUrl);
        if (fromAddress != null) return fromAddress;

        String html = fetch(pageUrl, null, userAgent, "text/html");
        return html == null ? null : firstId(html);
    }

    /** The first id the document mentions, which is the clip the player opens with. */
    @Nullable
    private static String firstId(String html) {
        Matcher m = MEDIA_ID.matcher(html);
        while (m.find()) {
            for (int group = 1; group <= m.groupCount(); group++) {
                String id = m.group(group);
                if (!TextUtils.isEmpty(id)) return id;
            }
        }
        return null;
    }

    @Nullable
    private String fetch(String url, @Nullable String referer, String userAgent, String accept) {
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", TextUtils.isEmpty(userAgent) ? Http.DEFAULT_UA : userAgent)
                .header("Accept", accept)
                .get();
        if (!TextUtils.isEmpty(referer)) builder.header("Referer", referer);

        try (Response response = Http.client().newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                Log.i(MediaRegistry.DIAG, "fandom fetch HTTP " + response.code() + " " + url);
                return null;
            }
            ResponseBody body = response.body();
            return body == null ? null : body.string();
        } catch (IOException e) {
            Log.i(MediaRegistry.DIAG, "fandom fetch failed: " + e.getMessage());
            return null;
        }
    }
}
