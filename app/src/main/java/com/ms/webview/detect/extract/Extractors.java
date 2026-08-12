package com.ms.webview.detect.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The extractor registry.
 *
 * <p>These are the most breakage-prone part of the app — every one of these platforms
 * reshuffles its JSON without notice. Keeping them behind one list makes them cheap to fix and
 * an obvious candidate for remote configuration later.
 */
public final class Extractors {

    /** The reader for hosts nobody has claimed. Selected only when no other one applies. */
    private static final SiteExtractor GENERIC = new GenericJsonExtractor();

    /**
     * One file per platform, deliberately — including where two of them serve near-identical
     * JSON. Sharing a reader saves a few lines once and costs every time one of the pair
     * changes, because the fix has to be made without disturbing the other. A platform that
     * misbehaves should be a single file to open.
     */
    private static final List<SiteExtractor> ALL = Arrays.asList(
            // Meta
            new InstagramExtractor(),
            new FacebookExtractor(),
            new ThreadsExtractor(),

            new TwitterExtractor(),
            new TikTokExtractor(),
            new RedditExtractor(),
            new VimeoExtractor(),
            new DailymotionExtractor(),
            new PinterestExtractor(),
            new TwitchExtractor(),
            new BilibiliExtractor(),
            new VkExtractor(),
            new LinkedInExtractor(),
            new ImdbExtractor(),
            new TumblrExtractor(),
            new FandomExtractor(),
            new FlickrExtractor(),

            // Short form
            new KwaiExtractor(),
            new LikeeExtractor(),
            new SnapchatExtractor(),
            new ShareChatExtractor(),

            GENERIC);

    private Extractors() {
    }

    /**
     * @param pageHost    host of the page the user is on
     * @param requestHost host the response actually came from
     *
     * <p>Both matter. An embedded player fetches its config from its own domain while the page
     * is somebody else's — a Vimeo iframe on a news site is still Vimeo, and matching only the
     * page would miss every embed.
     */
    public static List<SiteExtractor> forHosts(String pageHost, String requestHost) {
        String page = lower(pageHost);
        String request = lower(requestHost);

        List<SiteExtractor> applicable = new ArrayList<>(3);
        for (SiteExtractor extractor : ALL) {
            if (extractor == GENERIC) continue;
            if (extractor.appliesTo(page) || extractor.appliesTo(request)) {
                applicable.add(extractor);
            }
        }

        // The generic reader is a fallback, not a companion. Running it beside a dedicated
        // extractor undoes the dedicated one's judgement: on a Fandom page it treated the
        // player's whole queue as a quality ladder and offered twenty-seven videos where one
        // was playing. A host that has been claimed is read by its own extractor alone.
        if (applicable.isEmpty()) applicable.add(GENERIC);
        return applicable;
    }

    private static String lower(String host) {
        return host == null ? "" : host.toLowerCase(Locale.US);
    }
}
