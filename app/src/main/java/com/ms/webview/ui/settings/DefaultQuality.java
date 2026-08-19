package com.ms.webview.ui.settings;

import androidx.annotation.StringRes;

import com.ms.webview.R;

/**
 * What quality a download takes when nobody says — screen 10's first row.
 *
 * <p><b>Smallest</b> by default, which settles the design's open question. Asking on every single
 * download is one extra tap forever, and the sheet is still one tap away for the times it
 * matters. Smallest rather than best because the two mistakes are not equal: guessing too low
 * costs a rewatch, guessing too high costs somebody's data allowance.
 *
 * <p>The choice is what the detection sheet opens on, so this is not only a shortcut — it is the
 * sheet's starting position. {@link #ASK} is the one value that preselects nothing, which is what
 * "always ask" has to mean for it to be honest.
 */
public enum DefaultQuality {

    ASK(R.string.quality_always_ask, R.string.quality_always_ask_body),
    BEST(R.string.quality_best, R.string.quality_best_body),
    UP_TO_720(R.string.quality_up_to_720, R.string.quality_up_to_720_body),
    SMALLEST(R.string.quality_smallest, R.string.quality_smallest_body),
    AUDIO(R.string.quality_audio_only, R.string.quality_audio_only_body);

    @StringRes
    public final int label;

    /**
     * What choosing this costs — screen 17, panel A.
     *
     * <p>Beside the label rather than in the sheet, because the two have to move together: an
     * option renamed without its consequence renamed is how a list starts lying.
     */
    @StringRes
    public final int body;

    DefaultQuality(@StringRes int label, @StringRes int body) {
        this.label = label;
        this.body = body;
    }

    /** Falls back to the default rather than throwing on a value written by an older build. */
    public static DefaultQuality of(String name) {
        if (name != null) {
            for (DefaultQuality option : values()) {
                if (option.name().equals(name)) return option;
            }
        }
        return SMALLEST;
    }
}
