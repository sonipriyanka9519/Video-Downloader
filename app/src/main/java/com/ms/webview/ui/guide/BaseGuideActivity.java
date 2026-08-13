package com.ms.webview.ui.guide;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.ms.webview.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How to get a video out of one site.
 *
 * <p>Each site has an activity of its own, and each of those is four lines: its name, its mark,
 * its address, and its steps. Everything that is the same on every one of them — the paste box,
 * the pager, the dots, the button at the bottom — is here, so a change to the shape of a guide is
 * made once rather than four times.
 */
public abstract class BaseGuideActivity extends AppCompatActivity {

    /** The address the viewer settled on, handed back for the browser to open. */
    public static final String EXTRA_URL = "url";

    private EditText input;
    private TextView error;

    @StringRes
    protected abstract int siteName();

    /** The site's own mark, for the header — this is a guide about Instagram, not about us. */
    @DrawableRes
    protected abstract int siteIcon();

    /** Where the site lives, for the button at the bottom. */
    protected abstract String siteUrl();

    /** The site's own Android app, tried before any browser. */
    protected abstract String appPackage();

    /**
     * The hosts a link must be on to be accepted here.
     *
     * <p>Every shortener and mobile subdomain the site actually uses, because a viewer pasting a
     * link has no idea which of them the share sheet handed them — {@code fb.watch} and
     * {@code m.facebook.com} are both Facebook to everyone except a string comparison.
     */
    protected abstract String[] linkHosts();

    protected abstract GuideStep[] steps();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
        applySystemBarInsets();
        bindViews();
        bindSteps();
    }

    private void bindViews() {
        input = findViewById(R.id.guideInput);
        error = findViewById(R.id.guideError);

        ((ImageView) findViewById(R.id.guideIcon)).setImageResource(siteIcon());
        ((TextView) findViewById(R.id.guideName)).setText(siteName());
        ((TextView) findViewById(R.id.btnOpenSiteLabel))
                .setText(getString(R.string.guide_open_site, getString(siteName())));

        findViewById(R.id.btnGuideBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnGuideSearch).setOnClickListener(v -> openPastedLink());
        findViewById(R.id.btnGuidePaste).setOnClickListener(v -> pasteFromClipboard());
        findViewById(R.id.btnOpenSite).setOnClickListener(v -> openSiteElsewhere());

        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (!go) return false;
            openPastedLink();
            return true;
        });

        // The complaint goes away as soon as the viewer starts fixing what it complained about.
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                error.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Every step of every method in one pager, with a dot each.
     *
     * <p>The dots are built from the number of steps rather than declared, so a method that grows
     * a third picture grows a third dot without anyone remembering to add one.
     */
    private void bindSteps() {
        GuideStep[] steps = steps();
        ViewPager2 pager = findViewById(R.id.guidePager);
        LinearLayout dots = findViewById(R.id.guideDots);

        pager.setAdapter(new GuideStepAdapter(steps));

        int size = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._6sdp);
        int gap = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._3sdp);

        dots.removeAllViews();
        for (int i = 0; i < steps.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(i == 0 ? 0 : gap);
            dot.setLayoutParams(params);
            dots.addView(dot);
        }
        highlightDot(dots, 0);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                highlightDot(dots, position);
            }
        });
    }

    private static void highlightDot(ViewGroup dots, int selected) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setBackgroundResource(i == selected
                    ? R.drawable.bg_guide_dot_active : R.drawable.bg_guide_dot_inactive);
        }
    }

    // ------------------------------------------------------------------ opening things

    /**
     * Opens the site somewhere else: its own app where that is installed, a browser where it is
     * not.
     *
     * <p>Deliberately never this app's browser, because of what the button is for. The first
     * method has the viewer share a video from the site into this app, and a share sheet only
     * exists where the site is a real app or a real browser tab. Opening our own WebView would
     * put them exactly where the guide is telling them not to be — and this app registers itself
     * as a handler for web links, so it has to be ruled out by name rather than by hoping.
     */
    private void openSiteElsewhere() {
        if (openInApp()) return;
        openInBrowser();
    }

    /**
     * The site's own app, by its package, and only if it is really there.
     *
     * <p>Two ways in, because the two answer different questions. A link intent aimed at the
     * package lands on the page itself where the app claims its own web addresses; a launch
     * intent only opens the app at whatever it was showing. The first is better and not always
     * available.
     */
    private boolean openInApp() {
        PackageManager packages = getPackageManager();

        Intent deepLink = new Intent(Intent.ACTION_VIEW, Uri.parse(siteUrl()))
                .setPackage(appPackage());
        if (deepLink.resolveActivity(packages) != null) {
            return launch(deepLink);
        }

        Intent launcher = packages.getLaunchIntentForPackage(appPackage());
        return launcher != null && launch(launcher);
    }

    /**
     * A browser, preferring whichever one the phone already treats as the default.
     *
     * <p>Only offered as a choice when the default cannot be used — when it is us, or when the
     * system would have shown a chooser anyway. Anything else would put a list in front of
     * somebody who has already told their phone which browser they want.
     */
    private void openInBrowser() {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(siteUrl()))
                .addCategory(Intent.CATEGORY_BROWSABLE);
        PackageManager packages = getPackageManager();

        ResolveInfo preferred = packages.resolveActivity(view, PackageManager.MATCH_DEFAULT_ONLY);
        if (preferred != null && !getPackageName().equals(preferred.activityInfo.packageName)
                && !"android".equals(preferred.activityInfo.packageName)) {
            if (launch(view)) return;
        }

        // No usable default. Offer everything that can open a web address except ourselves.
        List<Intent> others = new ArrayList<>();
        for (ResolveInfo info : packages.queryIntentActivities(view, 0)) {
            if (getPackageName().equals(info.activityInfo.packageName)) continue;
            others.add(new Intent(view).setComponent(new ComponentName(
                    info.activityInfo.packageName, info.activityInfo.name)));
        }
        if (others.isEmpty()) return;

        if (others.size() == 1) {
            launch(others.get(0));
            return;
        }
        Intent chooser = Intent.createChooser(others.remove(0),
                getString(R.string.guide_open_site, getString(siteName())));
        chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, others.toArray(new Parcelable[0]));
        launch(chooser);
    }

    private boolean launch(Intent intent) {
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            // Uninstalled between being listed and being launched. Not worth a crash.
            return false;
        }
    }

    /**
     * Fills the box from the clipboard.
     *
     * <p>The link is almost always already there — that is the whole point of the second method —
     * so this saves a long press and a menu on the one screen built around having copied
     * something.
     */
    private void pasteFromClipboard() {
        ClipboardManager clipboard = ContextCompat.getSystemService(this, ClipboardManager.class);
        if (clipboard == null || !clipboard.hasPrimaryClip()) return;

        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;

        CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null) return;

        String pasted = text.toString().trim();
        input.setText(pasted);
        input.setSelection(pasted.length());
    }

    /**
     * Opens what was pasted, if it is an address and if it is this site's.
     *
     * <p>Two separate complaints, because they are two different mistakes and the fix for each is
     * different: text that is not a link at all, and a link belonging to somewhere else. Being
     * told "that is not a link" while holding a perfectly good Instagram link would send someone
     * hunting for a typo that is not there.
     */
    private void openPastedLink() {
        String typed = input.getText().toString().trim();

        if (!URLUtil.isNetworkUrl(typed)) {
            // An empty box is not a mistake, it is somebody who has not pasted yet.
            if (TextUtils.isEmpty(typed)) {
                error.setVisibility(View.GONE);
            } else {
                showError(R.string.guide_invalid_link);
            }
            return;
        }
        if (!belongsToSite(typed)) {
            showError(R.string.guide_wrong_site);
            return;
        }

        Intent result = new Intent();
        result.putExtra(EXTRA_URL, typed);
        setResult(RESULT_OK, result);
        finish();
    }

    /** Both complaints name the site, so the same call covers either. */
    private void showError(@StringRes int message) {
        error.setText(getString(message, getString(siteName())));
        error.setVisibility(View.VISIBLE);
    }

    /**
     * Whether an address is on one of this site's hosts.
     *
     * <p>Matched on the host and on the end of it, so {@code m.facebook.com} and
     * {@code www.facebook.com} both pass while {@code notfacebook.com} does not — a plain
     * "contains" would have let the second through.
     */
    private boolean belongsToSite(String url) {
        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (TextUtils.isEmpty(host)) return false;

        host = host.toLowerCase(Locale.US);
        for (String allowed : linkHosts()) {
            if (host.equals(allowed) || host.endsWith("." + allowed)) return true;
        }
        return false;
    }

    /** The app draws behind the system bars; a screen of chrome has to stay clear of them. */
    private void applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.guideRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}
