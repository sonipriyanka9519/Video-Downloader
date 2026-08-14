package com.ms.webview.ui.guide;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/**
 * One step of the download walkthrough: what to do, and a picture of it being done.
 *
 * <p>The heading is two strings rather than one because the second is coloured — it is the noun
 * the step turns on ("go to <em>website</em>", "tap <em>download</em>"), and picking it out is
 * what lets the line be read at a glance instead of read.
 */
public class HowStep {

    @StringRes
    public final int lead;
    @StringRes
    public final int accent;
    @DrawableRes
    public final int image;

    public HowStep(@StringRes int lead, @StringRes int accent, @DrawableRes int image) {
        this.lead = lead;
        this.accent = accent;
        this.image = image;
    }
}
