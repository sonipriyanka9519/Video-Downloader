package com.ms.webview.detect.site;

/**
 * Dailymotion.
 *
 * <p>This file exists so Dailymotion can be tuned without touching anything else. Its
 * behaviour started as the default one — the point of creating it was not to change what
 * Dailymotion does on day one, but to give it somewhere to change that no other platform
 * reads. Anything altered below reaches Dailymotion and nothing but Dailymotion.
 */
public class DailymotionPolicy implements SitePolicy {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("dailymotion.com") || host.contains("dmcdn.net")
                || host.contains("dai.ly");
    }

    /**
     * Newest first, as elsewhere. Dailymotion's own pages are one video at a time; a playlist
     * advances by loading the next one rather than by handing over the whole queue at once, so
     * there is no prefetched run ahead of the viewer to order the other way.
     */
    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Per URL, not per page. A playlist rewrites the address as it advances and the document
     * title follows a beat later, so a title taken for the page as a whole lands on whichever
     * video is found during the gap. Pairing it to the address it arrived for is what keeps the
     * name with its own video.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * False. Dailymotion pages carry related videos and a playlist queue, and none of it is
     * prefetched as playable media — so listing what has been found is not the flood it is on a
     * feed, and demanding playback first would hide videos the user can legitimately save.
     */
    @Override
    public boolean playingOnly() {
        return false;
    }

    /**
     * False. The scanner reads the video id out of the address, and on a playlist the address
     * belongs to the playlist rather than to the clip inside it — so the two ids often fail to
     * meet, and refusing to look further would mean never recognising the video playing.
     */
    @Override
    public boolean strictHintMatch() {
        return false;
    }
}
