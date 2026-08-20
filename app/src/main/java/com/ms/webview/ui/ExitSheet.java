package com.ms.webview.ui;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ms.webview.R;
import com.ms.webview.ads.AdIds;
import com.ms.webview.ads.NativeAds;

/**
 * The question asked when Back would close the app.
 *
 * <p>Raised from the browser, at the one press that has nowhere left to go: the page history is
 * exhausted and the tab grid has already been passed through, so the next press ends the session.
 * Everything else Back does in this app is reversible, and this is the only step that is not.
 *
 * <p><b>Cancel is the safe answer and the cheap one.</b> Dismissing the sheet - by the button, by
 * Back, or by swiping it down - leaves the app exactly as it was. Only the one button closes it.
 *
 * <p>The ad it carries is destroyed with the sheet. A native ad held by a dismissed dialog keeps
 * the dialog's whole view tree alive, and this one can be raised and dismissed repeatedly in a
 * single sitting.
 */
public final class ExitSheet {

    private ExitSheet() {
    }

    /**
     * Shows the sheet, and runs {@code onExit} only if the viewer chooses to leave.
     *
     * <p>{@code onExit} is never run for a dismissal. That asymmetry is the point of the sheet: a
     * cancelled exit has to be indistinguishable from never having pressed Back.
     */
    public static void show(@Nullable Activity activity, Runnable onExit) {
        // No host, no question - the press still has to do what it was going to do.
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            onExit.run();
            return;
        }

        View content = LayoutInflater.from(activity)
                .inflate(R.layout.sheet_exit, null, false);

        BottomSheetDialog sheet =
                new BottomSheetDialog(activity, R.style.ThemeOverlay_Ds_BottomSheet);
        sheet.setContentView(content);

        ViewGroup adSlot = content.findViewById(R.id.exitAdSlot);
        NativeAds.load(activity, adSlot, AdIds.nativeAd());
        // With the sheet, not with the screen: this dialog can be raised again a moment later, and
        // an ad left attached to a dismissed one is a leaked view tree per press.
        sheet.setOnDismissListener(d -> NativeAds.destroy(adSlot));

        content.findViewById(R.id.btnExitCancel).setOnClickListener(v -> sheet.dismiss());
        content.findViewById(R.id.btnExitConfirm).setOnClickListener(v -> {
            // Dismissed first, so the sheet is gone before the activity starts going away and
            // cannot be left showing on a window that no longer exists.
            sheet.dismiss();
            onExit.run();
        });

        sheet.show();
    }
}
