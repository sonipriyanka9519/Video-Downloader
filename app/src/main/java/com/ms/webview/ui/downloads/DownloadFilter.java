package com.ms.webview.ui.downloads;

import android.content.Context;

import androidx.annotation.StringRes;

import com.ms.webview.R;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.detect.MediaKind;

/**
 * The chip row on the library — screen 06.
 *
 * <p>Chips rather than sub-tabs, and the difference is not cosmetic. A tab says these are
 * separate lists; a chip says this is the same list, narrowed. Downloads are one library and the
 * viewer is only ever choosing how much of it to look at.
 *
 * <p>One is always chosen, and {@link #ALL} is what "none" means.
 */
public enum DownloadFilter {

    ALL(R.string.filter_all),
    VIDEOS(R.string.filter_videos),
    AUDIO(R.string.filter_audio),
    UNWATCHED(R.string.filter_unwatched);

    @StringRes
    public final int label;

    DownloadFilter(@StringRes int label) {
        this.label = label;
    }

    public boolean accepts(Context context, DownloadEntity d) {
        if (d == null) return false;
        switch (this) {
            case VIDEOS:
                return d.kind != MediaKind.AUDIO;
            case AUDIO:
                return d.kind == MediaKind.AUDIO;
            case UNWATCHED:
                // Anything still arriving has certainly not been watched, and asking the store
                // about a file with no published uri yet would answer no in any case.
                return WatchedStore.isUnwatched(context, d.outputUri);
            case ALL:
            default:
                return true;
        }
    }
}
