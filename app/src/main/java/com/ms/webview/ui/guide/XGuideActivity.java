package com.ms.webview.ui.guide;

import com.ms.webview.R;

/** X: share the post into this app, or copy its link and bring it back. */
public class XGuideActivity extends BaseGuideActivity {

    @Override
    protected int siteName() {
        return R.string.guide_x;
    }

    @Override
    protected int siteIcon() {
        return R.drawable.brand_x;
    }

    @Override
    protected String siteUrl() {
        return "https://x.com";
    }

    /** Still the old package name: the app was renamed, its identifier was not. */
    @Override
    protected String appPackage() {
        return "com.twitter.android";
    }

    /** twitter.com links still work and are still being shared, so both names are accepted. */
    @Override
    protected String[] linkHosts() {
        return new String[]{"x.com", "twitter.com", "t.co"};
    }

    @Override
    protected GuideStep[] steps() {
        // The same four steps the browser raises in a dialog, so the two cannot disagree.
        return GuideSite.X.steps;
    }
}
