package com.ms.webview.ui.guide;

import com.ms.webview.R;

/** Facebook: share the post into this app, or copy its link and bring it back. */
public class FacebookGuideActivity extends BaseGuideActivity {

    @Override
    protected int siteName() {
        return R.string.guide_facebook;
    }

    @Override
    protected int siteIcon() {
        return R.drawable.brand_facebook;
    }

    @Override
    protected String siteUrl() {
        return "https://www.facebook.com";
    }

    @Override
    protected String appPackage() {
        return "com.facebook.katana";
    }

    /** fb.watch is what the app's own share sheet hands out; fb.com is the short spelling. */
    @Override
    protected String[] linkHosts() {
        return new String[]{"facebook.com", "fb.watch", "fb.com", "fb.me"};
    }

    @Override
    protected GuideStep[] steps() {
        // The same four steps the browser raises in a dialog, so the two cannot disagree.
        return GuideSite.FACEBOOK.steps;
    }
}
