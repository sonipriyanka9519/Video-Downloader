package com.ms.webview.ui.guide;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.R;
import com.ms.webview.core.Formats;

/**
 * The guide for one site — screen 15, panel A.
 *
 * <p>Raised over the page rather than in place of it, because the explanation is about what to do
 * on that page: sending someone to a separate screen first meant reading the instructions,
 * dismissing them, and only then seeing the thing they described.
 *
 * <p>It exists only for the sites where in-browser detection is unreliable and the native app is
 * the dependable route, which is why the primary action leaves for that app — and says so plainly,
 * including when the app is not installed and the tap will go to the store instead.
 */
public final class GuideDialog {

    private GuideDialog() {
    }

    /**
     * Builds and shows it.
     *
     * @param onDismissed run however it is closed — the cross, Got it, back, or a tap outside. The
     *                    caller uses it to put up the button that raises this again, so it has to
     *                    fire for every way out and not only for the obvious one.
     * @return the dialog, so the caller can take it down when its screen goes
     */
    public static AlertDialog show(Context context, GuideSite site,
                                   DialogInterface.OnDismissListener onDismissed) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_guide, null, false);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_Ds_Dialog)
                .setView(content)
                .create();

        String siteName = context.getString(site.name);
        ((TextView) content.findViewById(R.id.guideTitle))
                .setText(context.getString(R.string.guide_title, siteName));

        bindPager(context, content, site);
        bindOpen(context, content, site, dialog, siteName);
        bindDismissal(content, dialog);

        dialog.setOnDismissListener(onDismissed);
        dialog.show();

        // Widened after showing, and this is what stops the pages overlapping.
        //
        // A dialog measures its custom view without a firm width, and a ViewPager2 given no firm
        // width lays its pages out at whatever they ask for rather than one screenful each — so the
        // next step showed along the right edge of the one being read. Setting the window to match
        // its parent gives the pager an exact width, and the dialog theme's own insets keep the card
        // clear of the screen edges.
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    /**
     * The primary action, named for what it will actually do.
     *
     * <p>"Open Instagram app" on a phone without Instagram is a promise the tap cannot keep, so the
     * button reads "Get Instagram" there and goes to the store listing. Asked once, when the dialog
     * is built: an app cannot be installed while a dialog is up over it.
     */
    private static void bindOpen(Context context, View content, GuideSite site,
                                 AlertDialog dialog, String siteName) {
        boolean installed = SiteLauncher.isAppInstalled(context, site.appPackage);

        MaterialButton open = content.findViewById(R.id.btnGuideDialogOpen);
        open.setText(context.getString(
                installed ? R.string.guide_open_app : R.string.guide_get_app, siteName));
        open.setOnClickListener(v -> {
            // Closed with the tap. Coming back to a guide nobody is reading any more is worse than
            // having to raise it again from the button beside the download button.
            dialog.dismiss();
            if (installed) {
                SiteLauncher.open(context, site.url, site.appPackage,
                        context.getString(R.string.guide_open_site, siteName));
            } else {
                SiteLauncher.openStore(context, site.appPackage);
            }
        });
    }

    /**
     * The two ways out, which mean the same thing.
     *
     * <p>There was a "Don't show again" checkbox here and it was removed by request. The guide is
     * raised by tapping a site's own shortcut, which is a deliberate act — so nothing is silenced
     * permanently, and the cross and Got it both simply close it.
     */
    private static void bindDismissal(View content, AlertDialog dialog) {
        content.findViewById(R.id.btnGuideDialogClose).setOnClickListener(v -> dialog.dismiss());
        content.findViewById(R.id.btnGuideGotIt).setOnClickListener(v -> dialog.dismiss());
    }

    /**
     * The steps, with a dot each and a chip saying which one is in front.
     *
     * <p>The dots are built from the number of steps rather than declared, so a site that grows a
     * fifth picture grows a fifth dot without anyone remembering to add one.
     */
    private static void bindPager(Context context, View content, GuideSite site) {
        ViewPager2 pager = content.findViewById(R.id.guideDialogPager);
        LinearLayout dots = content.findViewById(R.id.guideDialogDots);
        TextView chip = content.findViewById(R.id.guideStepChip);
        TextView count = content.findViewById(R.id.guideStepCount);

        pager.setAdapter(new GuideStepAdapter(context.getString(site.name), Formats.hostOf(site.url), site.steps));
        count.setText(context.getString(R.string.guide_step_of, site.steps.length));

        int size = context.getResources().getDimensionPixelSize(R.dimen.ds_guide_dot);
        int gap = context.getResources().getDimensionPixelSize(R.dimen.ds_space_1);

        dots.removeAllViews();
        for (int i = 0; i < site.steps.length; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(i == 0 ? 0 : gap);
            dot.setLayoutParams(params);
            dots.addView(dot);
        }
        showStep(context, dots, chip, 0);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                showStep(context, dots, chip, position);
            }
        });
    }

    private static void showStep(Context context, ViewGroup dots, TextView chip, int selected) {
        chip.setText(context.getString(R.string.guide_step_n, selected + 1));

        int wide = context.getResources().getDimensionPixelSize(R.dimen.ds_guide_dot_active);
        int size = context.getResources().getDimensionPixelSize(R.dimen.ds_guide_dot);

        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            boolean active = i == selected;
            ViewGroup.LayoutParams params = dot.getLayoutParams();
            params.width = active ? wide : size;
            params.height = size;
            dot.setLayoutParams(params);
            dot.setBackgroundResource(active
                    ? R.drawable.ds_bg_guide_dot_active : R.drawable.ds_bg_guide_dot);
        }
    }
}
