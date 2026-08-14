package com.ms.webview.ui.downloads;

import androidx.annotation.StringRes;

import com.ms.webview.R;
import com.ms.webview.data.DownloadEntity;

import java.util.Comparator;

/**
 * The orders the downloads list can be put in.
 *
 * <p>Sorting rather than filtering, because hiding rows answers a question nobody on this screen is
 * asking. What people want of a list of saved videos is to find one — and finding is a matter of
 * where a thing is, not of whether it is there at all.
 */
public enum DownloadSort {

    NEWEST(R.string.sort_newest),
    OLDEST(R.string.sort_oldest),
    NAME(R.string.sort_name),
    LARGEST(R.string.sort_largest),
    SMALLEST(R.string.sort_smallest);

    @StringRes
    public final int label;

    DownloadSort(@StringRes int label) {
        this.label = label;
    }

    /**
     * Whether this order runs along the calendar.
     *
     * <p>Only then are day headings worth drawing. Sorted by name, consecutive rows jump between
     * months, and a heading above each one would be a heading per row — the list would be more
     * heading than list.
     */
    public boolean byDate() {
        return this == NEWEST || this == OLDEST;
    }

    public Comparator<DownloadEntity> comparator() {
        switch (this) {
            case OLDEST:
                return (a, b) -> Long.compare(whenOf(a), whenOf(b));
            case NAME:
                // Case-insensitive, because a list where "apple" sorts after "Zebra" looks broken
                // to everyone who is not a computer.
                return (a, b) -> nameOf(a).compareToIgnoreCase(nameOf(b));
            case LARGEST:
                return (a, b) -> Long.compare(sizeOf(b), sizeOf(a));
            case SMALLEST:
                return (a, b) -> Long.compare(sizeOf(a), sizeOf(b));
            case NEWEST:
            default:
                return (a, b) -> Long.compare(whenOf(b), whenOf(a));
        }
    }

    /**
     * When a download belongs to: the moment it finished, or the moment it started if it has not.
     *
     * <p>Shared with the adapter's day headings on purpose. Two answers to "what date is this" —
     * one for sorting and one for grouping — would put a row under the wrong heading.
     */
    public static long whenOf(DownloadEntity d) {
        return d.completedAt > 0 ? d.completedAt : d.createdAt;
    }

    private static String nameOf(DownloadEntity d) {
        if (d.title != null) return d.title;
        return d.fileName == null ? "" : d.fileName;
    }

    /** What is actually on disk, so a half-finished download sorts by what it has so far. */
    private static long sizeOf(DownloadEntity d) {
        return d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes;
    }
}
