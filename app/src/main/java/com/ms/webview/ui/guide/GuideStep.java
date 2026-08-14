package com.ms.webview.ui.guide;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

/**
 * One swipeable page of a guide: what to do, and a picture of it being done.
 *
 * <p>The instruction is a string again rather than something drawn into the picture. Baked in, it
 * could not be translated, could not be read aloud, and could not be corrected without redrawing
 * the screenshot — which is a lot of work to change one word. The picture shows the screen; the
 * line above it says what to do on that screen.
 */
public class GuideStep {

    @StringRes
    public final int title;
    @DrawableRes
    public final int image;

    public GuideStep(@StringRes int title, @DrawableRes int image) {
        this.title = title;
        this.image = image;
    }
}
