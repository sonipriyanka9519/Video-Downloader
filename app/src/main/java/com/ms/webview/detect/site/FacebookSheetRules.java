package com.ms.webview.detect.site;

import androidx.annotation.Nullable;

import com.ms.webview.detect.MediaItem;

import java.util.Collection;
import java.util.List;

/**
 * Facebook's sheet: each video once, and only the ones not yet watched.
 *
 * <p>Ordering is not Facebook's own — it is delegated to {@link DefaultSheetRules}, the same
 * arrangement Instagram uses, which puts the playing video first. Facebook once had an ordering
 * of its own and it grew three groups deep trying to place the current video correctly without
 * knowing which video it was. That is not a question ordering can answer, and this file does not
 * try again.
 *
 * <p>What is Facebook's own is what the list <em>holds</em>: one card per video, and nothing the
 * viewer has already been through.
 */
public class FacebookSheetRules implements SheetRules {

    private final DefaultSheetRules ordering;

    public FacebookSheetRules(SitePolicy policy) {
        this.ordering = new DefaultSheetRules(policy);
    }

    @Override
    public void sort(List<MediaItem> visible) {
        ordering.sort(visible);
    }

    @Override
    public boolean admits(MediaItem item, Collection<MediaItem> all) {
        for (MediaItem other : all) {
            if (other == item) continue;
            if (!sameVideo(item, other)) continue;
            if (beats(other, item)) {
                handOver(item, other);
                return false;
            }
        }

        if (item.playing) return true;
        if (!item.watched) return true;

        // Watched, and behind the viewer — unless there is nothing that is not, in which case
        // showing what was watched beats showing an empty sheet. A rule about what to leave out
        // must never be the reason there is nothing left.
        return nothingAhead(all);
    }

    private static boolean nothingAhead(Collection<MediaItem> all) {
        for (MediaItem item : all) {
            if (item.playing || !item.watched) return false;
        }
        return true;
    }

    /**
     * Whether two cards are the same video: the poster, and nothing else.
     *
     * <p>Facebook gives one video one thumbnail, and the addresses for it differ only in the
     * signature it stamps on each fetch — so comparing paths is exact. Where either card has no
     * poster the answer is no, and both are listed. A duplicate is the cost, and it is the right
     * one to pay.
     *
     * <p>Matching on the running time was tried here and had to come out. A duration from a
     * payload is seconds multiplied by a thousand, so a feed is full of clips that are exactly
     * 13,000 or exactly 15,000 milliseconds — collisions were routine, not rare. And a match here
     * <em>discards</em> a card: the survivor is a different video with different addresses, so
     * the card in view was replaced between being read and being pressed, and the download
     * started on a clip nobody chose. A rule that can hand back the wrong file is worse than no
     * rule.
     */
    private static boolean sameVideo(MediaItem a, MediaItem b) {
        String posterA = posterIdentity(a);
        return posterA != null && posterA.equals(posterIdentity(b));
    }

    @Nullable
    private static String posterIdentity(MediaItem item) {
        String path = FacebookPolicy.pathOf(item.posterUrl);
        return path.isEmpty() ? null : path;
    }

    /**
     * Which copy of a video the list keeps.
     *
     * <p>Showable first, above everything else: keeping a copy that cannot be offered over one
     * that can would remove the video from the sheet rather than merely list it oddly. Then the
     * one that has been recognised, so de-duplicating also repairs the ordering.
     *
     * <p>A strict order, ending in the group key, which is unique. Without that last step two
     * copies could each be beaten by the other and both vanish, or neither could and both stay.
     */
    private static boolean beats(MediaItem a, MediaItem b) {
        boolean showableA = a.presentable();
        boolean showableB = b.presentable();
        if (showableA != showableB) return showableA;
        if (a.playing != b.playing) return a.playing;
        if (a.seenAt != b.seenAt) return a.seenAt > b.seenAt;
        if (a.discoveredAt != b.discoveredAt) return a.discoveredAt < b.discoveredAt;
        return a.groupKey.compareTo(b.groupKey) < 0;
    }

    /**
     * Gives the surviving copy what the discarded one knew.
     *
     * <p>The copies are not interchangeable: one is the card that can be shown, the other is
     * often the card the player was recognised against. Discarding the second silently took the
     * sighting with it, and the video was then listed as one not yet reached.
     *
     * <p>Only the two facts that say the video has been on screen. Not the playing mark, which
     * the registry owns and sweeps clean each time it recognises something — writing to it here
     * would leave a mark nothing clears.
     */
    private static void handOver(MediaItem from, MediaItem to) {
        if (from.seenAt > to.seenAt) to.seenAt = from.seenAt;
        if (from.watched) to.watched = true;
    }
}
