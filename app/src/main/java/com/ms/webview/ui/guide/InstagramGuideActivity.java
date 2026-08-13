package com.ms.webview.ui.guide;

import com.ms.webview.R;

/** Instagram: share the reel into this app, or copy its link and bring it back. */
public class InstagramGuideActivity extends BaseGuideActivity {

    @Override
    protected int siteName() {
        return R.string.guide_instagram;
    }

    @Override
    protected int siteIcon() {
        return R.drawable.brand_instagram;
    }

    @Override
    protected String siteUrl() {
        return "https://www.instagram.com";
    }

    @Override
    protected String appPackage() {
        return "com.instagram.android";
    }

    /** instagr.am is the old shortener, still handed out by some share paths. */
    @Override
    protected String[] linkHosts() {
        return new String[]{"instagram.com", "instagr.am", "ig.me"};
    }

    @Override
    protected GuideStep[] steps() {
        return new GuideStep[]{
                new GuideStep(R.drawable.img_ig_share_1),
                new GuideStep(R.drawable.img_ig_share_2),
                new GuideStep(R.drawable.img_ig_copy_1),
                new GuideStep(R.drawable.img_ig_copy_2),
        };
    }
}
