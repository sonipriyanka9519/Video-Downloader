package com.ms.webview.ui.guide;

import androidx.annotation.LayoutRes;
import androidx.annotation.StringRes;

/**
 * One swipeable page of a guide: what to do, and a drawing of it being done.
 *
 * <p>The instruction is a string rather than something baked into the picture. Baked in it could not
 * be translated, could not be read aloud, and could not be corrected without redrawing an image.
 *
 * <p>The picture is a layout rather than a screenshot, which is the same rule the walkthrough
 * follows: shapes standing in for an interface, so nothing in it can be mistaken for a control on
 * this screen, and both themes come free. It also means one set of drawings serves every site --
 * the flow is the same on all of them, and only the name in the instruction changes.
 */
public class GuideStep {

    @StringRes
    public final int title;
    @LayoutRes
    public final int art;

    public GuideStep(@StringRes int title, @LayoutRes int art) {
        this.title = title;
        this.art = art;
    }
}