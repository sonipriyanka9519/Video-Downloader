package com.ms.webview.detect.site;

import androidx.annotation.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VK video.
 *
 * <p>Its own file, so what is set here reaches VK and nothing else.
 */
public class VkPolicy implements SitePolicy {

    /**
     * The owner/video pair VK writes into its addresses, wherever it appears.
     *
     * <p>Two shapes, and both matter. A video opened on its own is a path —
     * {@code /video-12345_67890}. A video opened from a feed or a community wall is a query on a
     * page whose path never changes — {@code /feed?z=video-12345_67890}. The pair is the only
     * thing common to both, which is what makes it the honest name for "which video is open".
     */
    private static final Pattern VIDEO_ID =
            Pattern.compile("video(-?\\d+)_(\\d+)", Pattern.CASE_INSENSITIVE);

    @Override
    public boolean appliesTo(String host) {
        return host.contains("vk.com") || host.contains("vkvideo.ru")
                || host.contains("vk.ru") || host.contains("userapi.com")
                || host.contains("vk-cdn.net") || host.contains("vkuservideo");
    }

    /**
     * The video the address names, or null when it names none.
     *
     * <p>Without this, moving between videos on a feed reads as standing still. The engine judges
     * a route change on the path alone — deliberately, because a site that appends a tracking
     * parameter after load would otherwise wipe the sheet — and on VK the path is exactly what
     * does not change when the viewer opens the next video from a wall. So the detector kept the
     * previous video's findings and listed them beside the new one.
     */
    @Nullable
    @Override
    public String routeKeyFor(String url) {
        if (url == null) return null;
        Matcher m = VIDEO_ID.matcher(url);
        return m.find() ? "vk:" + m.group(1) + "_" + m.group(2) : null;
    }

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Per URL. A VK page titles itself after the video open on it, and on a wall that title
     * arrives a beat after the address changes — pairing the two stops a title following the
     * viewer onto the next video.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * True. Opening another video replaces what is on screen, so the previous one's findings are
     * no longer an answer to what the page holds.
     *
     * <p>Judged on {@link #routeKeyFor(String)} rather than the path, so it follows the video
     * rather than the shape of the address.
     */
    @Override
    public boolean resetOnRouteChange() {
        return true;
    }

    /**
     * True. VK swaps videos without loading a page: the player is replaced in place and the new
     * payload is fetched before an injected hook is listening on it, so what the detector holds
     * after the swap is whatever the previous video left behind. Reloading gives it a clean page
     * and the new video's own payload.
     *
     * <p>Safe here for the reason it would not be on a feed: this fires on a change of video, not
     * on a change of address. VK rewrites its own address freely — tracking tags, a
     * {@code list=} on a playlist — and none of that reaches this, because the video is the same
     * video.
     */
    @Override
    public boolean reloadOnRouteChange() {
        return true;
    }

    /**
     * False. VK ships a full player payload for every recommendation beside the one being
     * watched, but that is filtered where it is found rather than hidden here: {@code
     * VkExtractor} takes only the video the address names and drops the rest.
     *
     * <p>Which leaves nothing for this rule to do except cause harm. Showing only what has been
     * recognised as playing requires recognising it, and a video that fails that test is not
     * counted as unresolved either — so the sheet reports finding nothing on a page where a video
     * is plainly running. Deciding what exists is the reliable fix; hiding what does is not.
     */
    @Override
    public boolean playingOnly() {
        return false;
    }

    /**
     * True. VK's ids are exact — the owner/video pair is in the address, in the payload, and in
     * the group name the extractor builds from it — so a name we do not hold means a video we
     * have not read yet, not one of the others. Falling back to a shared running time from there
     * is how one video's poster and title land on another.
     */
    @Override
    public boolean strictHintMatch() {
        return true;
    }
}
