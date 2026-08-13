package com.ms.webview.ui.guide;

import com.ms.webview.R;

/** Pinterest: send the pin to this app, or copy its link and bring it back. */
public class PinterestGuideActivity extends BaseGuideActivity {

    @Override
    protected int siteName() {
        return R.string.guide_pinterest;
    }

    @Override
    protected int siteIcon() {
        return R.drawable.brand_pinterest;
    }

    @Override
    protected String siteUrl() {
        return "https://www.pinterest.com";
    }

    @Override
    protected String appPackage() {
        return "com.pinterest";
    }

    /**
     * Pinterest is the awkward one: it runs a country domain per market, and the app hands out
     * whichever matches the phone's region, so a link from a UK phone arrives on pinterest.co.uk.
     * The common ones are listed rather than guessed at, plus pin.it, which is what the share
     * sheet produces most of the time.
     */
    @Override
    protected String[] linkHosts() {
        return new String[]{
                "pinterest.com", "pin.it",
                "pinterest.co.uk", "pinterest.ca", "pinterest.com.au", "pinterest.de",
                "pinterest.fr", "pinterest.es", "pinterest.it", "pinterest.jp",
                "pinterest.ru", "pinterest.se", "pinterest.ch", "pinterest.at",
                "pinterest.nz", "pinterest.ie", "pinterest.ph", "pinterest.cl",
                "pinterest.mx", "pinterest.pt", "pinterest.dk", "pinterest.nl",
        };
    }

    @Override
    protected GuideStep[] steps() {
        return new GuideStep[]{
                new GuideStep(R.drawable.img_pin_share_1),
                new GuideStep(R.drawable.img_pin_share_2),
                new GuideStep(R.drawable.img_pin_copy_1),
                new GuideStep(R.drawable.img_pin_copy_2),
        };
    }
}
