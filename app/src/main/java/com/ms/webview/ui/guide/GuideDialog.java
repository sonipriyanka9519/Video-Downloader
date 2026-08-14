package com.ms.webview.ui.guide;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.R;

/**
 * The guide for a site, shown over the page rather than in place of it.
 *
 * <p>Raised when a shortcut opens one of the sites worth explaining. Over the page, because the
 * explanation is about what to do on the page — sending someone to a separate screen first meant
 * reading the instructions, dismissing them, and only then seeing the thing they described.
 */
public final class GuideDialog {

    private GuideDialog() {
    }

    /**
     * Builds and shows it.
     *
     * @param onDismissed run however it is closed — the cross, back, or a tap outside. The caller
     *                    uses it to put up the button that raises this again, so it has to fire
     *                    for every way out and not only for the obvious one.
     * @return the dialog, so the caller can take it down when its screen goes
     */
    public static AlertDialog show(Context context, GuideSite site,
                                   DialogInterface.OnDismissListener onDismissed) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.dialog_guide, null, false);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setView(content)
                .create();

        bindPager(context, content, site);
        content.findViewById(R.id.btnGuideDialogClose).setOnClickListener(v -> dialog.dismiss());

        // Named, because "Open" alone would not say where. The button leaves for the site's own
        // app, so the dialog closes with it: coming back to a guide nobody is reading any more is
        // worse than having to raise it again from the button beside the download button.
        TextView open = content.findViewById(R.id.btnGuideDialogOpen);
        open.setText(context.getString(R.string.guide_open_site, context.getString(site.name)));
        open.setOnClickListener(v -> {
            dialog.dismiss();
            SiteLauncher.open(context, site.url, site.appPackage, open.getText().toString());
        });

        dialog.setOnDismissListener(onDismissed);

        dialog.show();
        return dialog;
    }

    /**
     * The steps, with a dot each.
     *
     * <p>The dots are built from the number of steps rather than declared, so a method that grows
     * a third picture grows a third dot without anyone remembering to add one.
     */
    private static void bindPager(Context context, View content, GuideSite site) {
        ViewPager2 pager = content.findViewById(R.id.guideDialogPager);
        LinearLayout dots = content.findViewById(R.id.guideDialogDots);

        pager.setAdapter(new GuideStepAdapter(site.steps));

        int size = context.getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._6sdp);
        int gap = context.getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._3sdp);

        dots.removeAllViews();
        for (int i = 0; i < site.steps.length; i++) {
            View dot = new View(context);
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
}
