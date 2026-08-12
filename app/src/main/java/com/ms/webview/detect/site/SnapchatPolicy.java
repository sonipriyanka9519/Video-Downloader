package com.ms.webview.detect.site;

/**
 * Snapchat.
 *
 * <p>Its own file, so what is set here reaches Snapchat and nothing else.
 */
public class SnapchatPolicy implements SitePolicy {

    @Override
    public boolean appliesTo(String host) {
        return host.contains("snapchat.com") || host.contains("sc-cdn.net")
                || host.contains("snapchat.dev") || host.contains("snap.com");
    }

    @Override
    public Order order() {
        return Order.NEWEST;
    }

    /**
     * Per URL. Snapchat titles the document after the snap in view and rewrites both together as
     * the feed scrolls, so pairing them stops a title following the viewer onto the next clip.
     */
    @Override
    public PageTitleUse pageTitleUse() {
        return PageTitleUse.PER_URL;
    }

    /**
     * False, and this has to be false however much a spotlight address looks like a reason to
     * clear.
     *
     * <p>Snapchat writes the snap into the path — {@code /@name/spotlight/<id>} — and rewrites it
     * as the viewer scrolls, so every scroll reads as a route change. Clearing on that destroys
     * the one thing detection depends on: Snapchat describes the whole shelf of snaps in the
     * response that loads the page, and never sends it again. Discard it and the snap scrolled to
     * has no payload behind it and no way to acquire one, so it is not merely undetected — it is
     * permanently undetectable, while the address bar shows its id the whole time.
     *
     * <p>That is what made a snap detect on arrival and then never again after a scroll.
     *
     * <p>Nothing is lost by leaving it off. Clearing was there to keep the sheet to the snap in
     * view, and {@link #playingOnly()} with {@link #keepWatched()} already do that — from what is
     * on screen rather than from what the address says, which is both more accurate and does not
     * throw anything away to achieve it.
     */
    @Override
    public boolean resetOnRouteChange() {
        return false;
    }

    /**
     * True — and on most platforms this would be the wrong call, so the reason it is right here
     * is worth setting down.
     *
     * <p>The rule admits only videos that have actually been on screen. It fails badly wherever
     * the video on screen cannot be recognised: the sheet reports finding nothing on a page with
     * a video plainly playing, and does not even count the withheld videos as unresolved, so
     * there is no clue as to why. That is what happened on Fandom, on Tumblr, and on Facebook.
     *
     * <p>Snapchat is the case those were not. Its player is a plain {@code <video>} with the
     * file's own address in {@code src} — not the {@code blob:} a MediaSource player reports —
     * and matching an address against the addresses already detected is exact. No id to line up,
     * no poster to compare, no guessing from running time.
     *
     * <p>What it buys is the thing a payload cannot give: Snapchat's first response describes the
     * whole shelf of snaps, which is why opening a profile listed videos the viewer had not
     * reached and in most cases never would.
     */
    @Override
    public boolean playingOnly() {
        return true;
    }

    /**
     * False. The sheet holds the snap on screen and nothing else — scroll on, and the previous
     * one is no longer offered.
     *
     * <p>Normally the opposite is right: having watched something is a reason to offer it, not to
     * withdraw it. Here it would undo the rule above. Snapchat is scrolled continuously and every
     * snap passed through is marked watched, so keeping them turns a sheet meant to show the
     * current snap into the same full shelf {@link #playingOnly()} was set to prevent — arriving
     * a few seconds later instead of at once.
     */
    @Override
    public boolean keepWatched() {
        return false;
    }

    /**
     * True. What Snapchat's payload states about a snap — the file's address, its running time,
     * its cover — is the record its own player is handed, and a local decode only confirms it.
     *
     * <p>Without this a snap was detected and then sat there unofferable. Its addresses carry no
     * extension and an opaque query, so the probe has more than the usual chance of being refused
     * or of reading nothing useful back, and a refusal was enough to keep a perfectly playable
     * snap out of the sheet. The probe still runs for exact dimensions and byte size; it is no
     * longer what decides whether the snap can be offered at all.
     */
    @Override
    public boolean trustDeclaredMedia() {
        return true;
    }

    /**
     * False. Inert either way — the scanner produces no {@code snap:} hint, so there is never an
     * id to be strict about — and left at the value that keeps the weaker signals available for
     * the day it does.
     */
    @Override
    public boolean strictHintMatch() {
        return false;
    }
}
