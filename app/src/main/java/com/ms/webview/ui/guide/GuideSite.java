package com.ms.webview.ui.guide;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.ms.webview.R;

/**
 * The sites worth explaining, and what their explanation says.
 *
 * <p>Here rather than inside the guide screens because two things need it now. The guide screen
 * still shows these steps in full; the browser shows the same steps in a dialog over the page,
 * raised when a shortcut opens the site. Written down twice they would drift, and the version a
 * viewer saw would depend on how they got there.
 *
 * <p>Most sites are absent, and that is the ordinary case: you open them and scroll. Only the ones
 * people arrive at holding a copied link need saying anything about.
 */
public enum GuideSite {

    FACEBOOK("facebook", R.string.guide_facebook, R.drawable.brand_facebook,
            "https://www.facebook.com", "com.facebook.katana", new GuideStep[]{
            new GuideStep(R.string.guide_fb_step_1, R.drawable.img_fb_share_1),
            new GuideStep(R.string.guide_fb_step_2, R.drawable.img_fb_share_2),
            new GuideStep(R.string.guide_fb_step_3, R.drawable.img_fb_copy_1),
            new GuideStep(R.string.guide_fb_step_4, R.drawable.img_fb_copy_2),
    }),

    INSTAGRAM("instagram", R.string.guide_instagram, R.drawable.brand_instagram,
            "https://www.instagram.com", "com.instagram.android", new GuideStep[]{
            new GuideStep(R.string.guide_ig_step_1, R.drawable.img_ig_share_1),
            new GuideStep(R.string.guide_ig_step_2, R.drawable.img_ig_share_2),
            new GuideStep(R.string.guide_ig_step_3, R.drawable.img_ig_copy_1),
            new GuideStep(R.string.guide_ig_step_4, R.drawable.img_ig_copy_2),
    }),

    PINTEREST("pinterest", R.string.guide_pinterest, R.drawable.brand_pinterest,
            "https://www.pinterest.com", "com.pinterest", new GuideStep[]{
            new GuideStep(R.string.guide_pin_step_1, R.drawable.img_pin_share_1),
            new GuideStep(R.string.guide_pin_step_2, R.drawable.img_pin_share_2),
            new GuideStep(R.string.guide_pin_step_3, R.drawable.img_pin_copy_1),
            new GuideStep(R.string.guide_pin_step_4, R.drawable.img_pin_copy_2),
    }),

    // Still the old package name: the app was renamed, its identifier was not.
    X("x", R.string.guide_x, R.drawable.brand_x,
            "https://x.com", "com.twitter.android", new GuideStep[]{
            new GuideStep(R.string.guide_x_step_1, R.drawable.img_x_share_1),
            new GuideStep(R.string.guide_x_step_2, R.drawable.img_x_share_2),
            new GuideStep(R.string.guide_x_step_3, R.drawable.img_x_copy_1),
            new GuideStep(R.string.guide_x_step_4, R.drawable.img_x_copy_2),
    });

    /** The shortcut this belongs to, as {@code Shortcuts} names it. */
    public final String shortcutId;
    @StringRes
    public final int name;
    @DrawableRes
    public final int icon;
    /** Where the site lives, for the button that leaves for it. */
    public final String url;
    /** The site's own Android app, tried before any browser. */
    public final String appPackage;
    public final GuideStep[] steps;

    GuideSite(String shortcutId, @StringRes int name, @DrawableRes int icon,
              String url, String appPackage, GuideStep[] steps) {
        this.shortcutId = shortcutId;
        this.name = name;
        this.icon = icon;
        this.url = url;
        this.appPackage = appPackage;
        this.steps = steps;
    }

    /** The guide for a shortcut, or null where there is none — which is most of them. */
    @Nullable
    public static GuideSite forShortcut(@Nullable String shortcutId) {
        if (shortcutId == null) return null;
        for (GuideSite site : values()) {
            if (site.shortcutId.equals(shortcutId)) return site;
        }
        return null;
    }
}
