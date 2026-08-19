package com.ms.webview.ui.lock;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ms.webview.R;

/**
 * What the private folder is, offered before it is turned on — screen 11, panel D.
 *
 * <p>Raised whenever somebody reaches for the folder and there is no lock yet: from the Settings
 * row, from the Private chip, and from "Move to private" on a video. Three doors into one thing,
 * and all three are the first time it is being explained.
 *
 * <p>Declining is a real answer, so every way out of the sheet is one — the folder is not something
 * the app needs the viewer to have.
 */
public final class PrivateFolderIntro {

    private PrivateFolderIntro() {
    }

    /**
     * @param onTurnOn run when the viewer accepts; the caller takes them on to setup
     */
    public static void show(@NonNull Context context, @NonNull Runnable onTurnOn) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.sheet_private_intro, null, false);

        BottomSheetDialog sheet = new BottomSheetDialog(context, R.style.ThemeOverlay_Ds_BottomSheet);
        sheet.setContentView(content);

        content.findViewById(R.id.btnPrivateTurnOn).setOnClickListener(v -> {
            sheet.dismiss();
            onTurnOn.run();
        });
        sheet.show();
    }
}
