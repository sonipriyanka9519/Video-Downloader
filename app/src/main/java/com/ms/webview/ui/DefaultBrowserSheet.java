package com.ms.webview.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ms.webview.R;

/**
 * The offer to become the phone's browser — raised by the browser on its own initiative, and by
 * the Settings row when the viewer goes looking for it.
 *
 * <p>One component for both, because it is one offer. The Settings row used to leave straight for
 * Android's own default-apps screen, which is a jump out of the app with no explanation of what is
 * being asked; the sheet says what changes for them first and then asks, exactly as it does when
 * the browser raises it unprompted.
 *
 * <p>Nothing here decides <em>whether</em> to ask — see {@link DefaultBrowser} for that, and the
 * browser's own quiet period for when. This only puts it on screen.
 */
public final class DefaultBrowserSheet {

    /**
     * Deliberately darker than a sheet dims by default. The offer covers the lower half of the
     * screen, so a light scrim leaves what is above it looking as live as ever — two things asking
     * to be looked at, one of which cannot be touched.
     */
    private static final float SCRIM = 0.75f;

    private DefaultBrowserSheet() {
    }

    /**
     * @param onAccept   the viewer said yes; the caller sends them where they can grant it
     * @param onDeclined every other way out — the cross, a swipe down, a back press, a tap
     *                   outside. All four mean the same thing, so there is one callback rather
     *                   than four. Not called when the offer was accepted.
     * @return the dialog, so a caller that has to dismiss it later can hold on to it
     */
    public static BottomSheetDialog show(@NonNull Context context, @NonNull Runnable onAccept,
                                         @Nullable Runnable onDeclined) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.sheet_default_browser, null, false);

        // The overlay on the dialog, not only on the layout inside it. Without it the sheet's own
        // container and scrim come from Material's defaults, which is what tinted every dialog in
        // the app that lavender-grey.
        BottomSheetDialog sheet = new BottomSheetDialog(context,
                R.style.ThemeOverlay_Ds_BottomSheet);
        sheet.setContentView(content);

        Window window = sheet.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(SCRIM);
        }

        // The sheet's own container is opaque and square. Left as it is, its corners sit behind the
        // rounded ones this layout draws and the rounding shows only as two pale triangles — so the
        // container gets out of the way and the layout provides the surface.
        View container = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (container != null) container.setBackgroundColor(Color.TRANSPARENT);

        // Which way it was left has to be remembered, because dismiss() is how it closes either
        // way: a caller that raises something else after a decline must not raise it after a yes,
        // when the viewer is already on their way to Android's own screen.
        final boolean[] accepted = {false};

        content.findViewById(R.id.btnSetDefault).setOnClickListener(v -> {

            accepted[0] = true;
            sheet.dismiss();
            onAccept.run();
        });
        content.findViewById(R.id.btnDefaultClose).setOnClickListener(v -> sheet.dismiss());

        // One listener for every way out, the cross included — dismiss() lands here too.
        if (onDeclined != null) {
            sheet.setOnDismissListener(d -> {
                if (!accepted[0]) onDeclined.run();
            });
        }

        sheet.show();
        return sheet;
    }
}
