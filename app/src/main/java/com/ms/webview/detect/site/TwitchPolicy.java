package com.ms.webview.detect.site;

import androidx.annotation.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Twitch.
 *
 * <p>Its own file, so what is set here reaches Twitch and nothing else. Only the two things
 * Twitch was asked for differ from what every other host does: a route change reloads the page,
 * and it starts the search over. The rest is left as it was.
 */
public class TwitchPolicy implements SitePolicy {

    /** twitch.tv/channel/clip/Slug and clips.twitch.tv/Slug. */
    private static final Pattern CLIP = Pattern.compile(
            "(?:clips\\.twitch\\.tv/|twitch\\.tv/(?:[^/]+/)?clip/)([A-Za-z0-9_-]+)",
            Pattern.CASE_INSENSITIVE);
    /** twitch.tv/videos/123456 and the older channel/video/123456 form. */
    private static final Pattern VOD = Pattern.compile(
            "twitch\\.tv/(?:[^/]+/)?videos?/(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean appliesTo(String host) {
        return host.contains("twitch.tv") || host.contains("ttvnw.net")
                || host.contains("jtvnw.net");
    }

    /**
     * What the address is a view of: the clip, the VOD, or failing both the bare path.
     *
     * <p>Named in the same terms {@code TwitchPageResolver} names its findings, so the two agree
     * on what counts as one video.
     *
     * <p>Answering for every address rather than only the ones it recognises is the point. Twitch
     * rewrites its own address constantly and without loading anything — a {@code ?t=} timestamp
     * as a VOD plays, a {@code tt_content} tag on arrival, a sort order on a directory. Judged on
     * the whole address, each of those is a new route, and with reloading turned on each would
     * reload the page — after which Twitch writes the parameter back and it happens again. Judged
     * on this, none of them is: the video did not change, so nothing happens.
     */
    @Nullable
    @Override
    public String routeKeyFor(String url) {
        if (url == null) return null;

        String clip = find(CLIP, url);
        if (clip != null) return "twitch:" + clip;

        String vod = find(VOD, url);
        if (vod != null) return "twitch:v" + vod;

        return "twitch:" + pathOf(url);
    }

    /**
     * The one clip or VOD this address is about, named as {@code TwitchPageResolver} names it.
     *
     * <p>A clip page is not only its clip. Twitch mobile builds a rail of further clips beneath
     * the one being watched and loads their sources with it, so the page genuinely holds several
     * videos and the detector genuinely finds them — five of them, on a page showing one. None of
     * them is the answer to "what am I watching", and the address says which one is.
     *
     * <p>Null for a channel or a directory, where the address names nothing and there is nothing
     * to narrow to. Advisory in any case: if nothing is held under this name the sheet shows
     * everything, so this can only ever reduce a list, never empty one.
     */
    @Nullable
    @Override
    public String soloPostFor(String pageUrl) {
        if (pageUrl == null) return null;

        String clip = find(CLIP, pageUrl);
        if (clip != null) return "twitch:" + clip;

        String vod = find(VOD, pageUrl);
        return vod == null ? null : "twitch:v" + vod;
    }

    /**
     * True. What comes back from Twitch's GraphQL endpoint is the record its own player is given:
     * the file addresses, signed; the running time; the poster. A local decode confirms what that
     * already says, and when the decode fails — a CDN that refuses a range request, a budget
     * spent on the rail of clips beside the one being watched — a clip that plays perfectly well
     * is reported as one that could not be opened for download.
     *
     * <p>The probe still runs for exact dimensions and byte size. It is no longer what decides
     * whether the clip can be offered.
     */
    @Override
    public boolean trustDeclaredMedia() {
        return true;
    }

    /**
     * True. Opening another stream, VOD or clip replaces what is on screen, so what was found on
     * the last one is no longer an answer to what the page holds.
     */
    @Override
    public boolean resetOnRouteChange() {
        return true;
    }

    /**
     * True. Twitch moves between videos without loading a page, and its player asks for what it
     * needs before an injected hook can be listening — which is the same reason the site needed a
     * resolver in the first place. Left alone, the detector holds whatever the previous video
     * left behind. Reloading gives it a clean page and the new video's own payload.
     *
     * <p>Safe here because it fires on a change of video rather than a change of address; see
     * {@link #routeKeyFor(String)}, which is what draws that distinction.
     */
    @Override
    public boolean reloadOnRouteChange() {
        return true;
    }

    // Everything below is what Twitch already did as an unlisted host, kept the same on purpose.

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_PAGE;
    }

    @Override
    public boolean playingOnly() {
        return false;
    }

    @Override
    public boolean strictHintMatch() {
        return false;
    }

    @Nullable
    private static String find(Pattern pattern, String url) {
        Matcher m = pattern.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** The path alone: no query, no fragment, and no host, so a redirect between www and m is
     *  not mistaken for a change of content. */
    private static String pathOf(String url) {
        String s = url;
        int cut = s.indexOf('#');
        if (cut >= 0) s = s.substring(0, cut);
        cut = s.indexOf('?');
        if (cut >= 0) s = s.substring(0, cut);

        int scheme = s.indexOf("//");
        if (scheme >= 0) {
            int start = s.indexOf('/', scheme + 2);
            s = start < 0 ? "/" : s.substring(start);
        }
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s.toLowerCase(Locale.US);
    }
}
