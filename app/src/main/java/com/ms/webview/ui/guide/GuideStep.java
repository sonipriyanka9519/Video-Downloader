package com.ms.webview.ui.guide;

import androidx.annotation.DrawableRes;

/**
 * One swipeable page of a guide: a picture, and nothing else.
 *
 * <p>It carried a method name, a step number, an instruction and a "done" flag once. All of that
 * now lives inside the picture, which is the only place it can be kept truthful — the words
 * describe a screen, and the screen is the picture.
 */
public class GuideStep {

    @DrawableRes
    public final int image;

    public GuideStep(@DrawableRes int image) {
        this.image = image;
    }
}
