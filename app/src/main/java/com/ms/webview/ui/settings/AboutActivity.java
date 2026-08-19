package com.ms.webview.ui.settings;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;

import com.ms.webview.R;
import com.ms.webview.ui.SystemBars;
import com.ms.webview.ui.growth.RatePrompt;
import com.ms.webview.ui.growth.ShareAppSheet;

/**
 * About — screen 17, panel D.
 *
 * <p>A screen rather than a sheet, unlike the rest of screen 17. The others are radio choices that
 * resolve in a tap; this is a page somebody arrives at to read, and two of its rows leave the app.
 *
 * <p><b>The privacy policy has to be reachable from here.</b> That is a Play requirement rather
 * than a preference, and it is the reason this screen exists at all rather than three loose rows at
 * the bottom of Settings.
 *
 * <p>The rows are built in code from the same {@code item_settings_row} the settings list uses, so
 * a row here and a row there cannot drift apart. What differs is the trailing glyph: the two legal
 * links that open a browser carry {@code open_in_new} rather than a chevron, because a chevron
 * promises another screen in this app and these do not deliver one.
 */
public class AboutActivity extends AppCompatActivity {

    private static final String TAG = "AboutActivity";

    /**
     * Where the legal text lives.
     *
     * <p>Constants rather than strings.xml: these are addresses, not copy, and a translator asked
     * to localise a URL will eventually change one.
     */
    private static final String PRIVACY_URL = "https://msdevstudio.github.io/webview/privacy.html";
    private static final String TERMS_URL = "https://msdevstudio.github.io/webview/terms.html";

    private LinearLayout list;

    public static void open(Context context) {
        context.startActivity(new Intent(context, AboutActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        // Clear of the status bar and the gesture area — the app is drawn edge to edge.
        SystemBars.pad(findViewById(R.id.aboutRoot));
        findViewById(R.id.btnAboutBack).setOnClickListener(v -> finish());

        ((TextView) findViewById(R.id.aboutVersion)).setText(
                getString(R.string.about_version, versionName(), versionCode()));

        list = findViewById(R.id.aboutList);
        buildRows();
    }

    private void buildRows() {
        section(R.string.about_support);
        // The subtitle is only on Rate: it is the one row asking for something rather than offering
        // it, and saying what it costs is the difference between a request and a nag.
        row(R.drawable.ic_star, R.string.setting_rate_app, getString(R.string.about_rate_body),
                R.drawable.ic_chevron_right, this::rate);
        row(R.drawable.ic_share, R.string.setting_share_app, null,
                R.drawable.ic_chevron_right, this::share);
        row(R.drawable.ic_mail, R.string.about_feedback, null,
                R.drawable.ic_chevron_right, this::feedback);

        divider();
        section(R.string.about_legal);
        // open_in_new, not a chevron: these leave for a browser, and a chevron would promise
        // another screen in this app.
        row(R.drawable.ic_shield, R.string.about_privacy, null,
                R.drawable.ic_open_in_new, () -> openLink(PRIVACY_URL));
        row(R.drawable.ic_gavel, R.string.about_terms, null,
                R.drawable.ic_open_in_new, () -> openLink(TERMS_URL));
        row(R.drawable.ic_code, R.string.about_licences, null,
                R.drawable.ic_chevron_right, this::licences);
    }

    // ------------------------------------------------------------------ actions

    /**
     * Raises screen 18's rate sheet rather than jumping straight to the store.
     *
     * <p>The same surface either way, so the row and the prompt say the same thing — and this way
     * "Send feedback" is on offer to somebody about to leave a bad review, which is the whole
     * point of that button being on the sheet.
     */
    private void rate() {
        RatePrompt.showNow(this);
    }

    /**
     * Raises screen 18's share sheet rather than the system chooser.
     *
     * <p>What goes out has this app's name on it, so it is shown before it is sent — and the sheet
     * carries Copy link, which the chooser cannot.
     */
    private void share() {
        ShareAppSheet.show(this);
    }

    private String storeUrl() {
        return "https://play.google.com/store/apps/details?id=" + getPackageName();
    }

    /**
     * Opens a mail draft already addressed and already labelled with the build.
     *
     * <p>The version goes in the subject because the first thing anybody answering a report has to
     * ask is which build it came from, and the person reporting it should not have to know.
     */
    private void feedback() {
        Intent mail = new Intent(Intent.ACTION_SENDTO)
                .setData(Uri.parse("mailto:" + getString(R.string.feedback_email)))
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_feedback_subject,
                        getString(R.string.app_name), versionName()));
        try {
            startActivity(mail);
        } catch (ActivityNotFoundException e) {
            // Said out loud rather than swallowed: the row did nothing, and a row that does
            // nothing without explanation reads as a broken app.
            Toast.makeText(this, R.string.about_no_mail_app, Toast.LENGTH_LONG).show();
        }
    }

    /**
     * The licences of everything this app ships.
     *
     * <p>Distribution terms for most of these libraries require the notice to travel with the
     * binary, so this is an obligation rather than a courtesy. The list is generated at build time
     * by the OSS plugin; until that is wired in, the row goes to the same place the policy does
     * rather than opening an empty screen.
     */
    private void licences() {
        openLink(PRIVACY_URL + "#licences");
    }

    /** @return false when nothing on the phone can open it, so a caller can try something else */
    private boolean openLink(String url) {
        if (TextUtils.isEmpty(url)) return false;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ the build

    /**
     * Asked of the package manager rather than read off BuildConfig.
     *
     * <p>BuildConfig is not generated in this module - AGP 8 stopped emitting it unless the build
     * file turns it on - and the settings list already answers this question the same way. One way
     * of naming the version, in both places that name it.
     */
    private String versionName() {
        PackageInfo info = ownPackage();
        return info == null || info.versionName == null ? "" : info.versionName;
    }

    private long versionCode() {
        PackageInfo info = ownPackage();
        if (info == null) return 0L;
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? info.getLongVersionCode() : info.versionCode;
    }

    @Nullable
    private PackageInfo ownPackage() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException impossible) {
            // The app asking about itself. Logged rather than swallowed, because if this ever
            // happens the version is the least of it.
            Log.w(TAG, "Own package not found", impossible);
            return null;
        }
    }

    // ------------------------------------------------------------------ rows

    private void section(@StringRes int label) {
        TextView view = (TextView) LayoutInflater.from(this)
                .inflate(R.layout.item_settings_section, list, false);
        view.setText(label);
        list.addView(view);
    }

    private void divider() {
        list.addView(LayoutInflater.from(this)
                .inflate(R.layout.item_settings_divider, list, false));
    }

    private void row(@DrawableRes int icon, @StringRes int title, @Nullable CharSequence subtitle,
                     @DrawableRes int trailing, Runnable action) {
        View view = LayoutInflater.from(this)
                .inflate(R.layout.item_settings_row, list, false);

        ((ImageView) view.findViewById(R.id.settingIcon)).setImageResource(icon);
        ((TextView) view.findViewById(R.id.settingTitle)).setText(title);

        TextView sub = view.findViewById(R.id.settingSubtitle);
        sub.setVisibility(TextUtils.isEmpty(subtitle) ? View.GONE : View.VISIBLE);
        sub.setText(subtitle);

        ImageView chevron = view.findViewById(R.id.settingChevron);
        chevron.setVisibility(View.VISIBLE);
        chevron.setImageResource(trailing);

        view.setOnClickListener(v -> action.run());
        list.addView(view);
    }
}
