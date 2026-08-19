package com.ms.webview.ui.growth;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ms.webview.R;

/**
 * Share this app — screen 18, panel D.
 *
 * <p>The message is shown before it is sent, which is why this exists rather than a straight jump
 * to the system chooser: what goes out carries this app's name and lands in somebody else's inbox,
 * so the person sending it should have read it first.
 *
 * <p>Four ways out, and the last is not a share target at all. <b>Copy link</b> is here because the
 * most common answer to "how do I send this to someone" is "somewhere you have not thought of", and
 * a link on the clipboard works in all of them.
 *
 * <p>Messages and Email are asked for by intent rather than by package name. Naming an app would
 * mean guessing which one somebody uses and being wrong on most phones; asking for the category
 * lets the system answer, and when it cannot, the sheet says so instead of doing nothing.
 */
public final class ShareAppSheet {

    private ShareAppSheet() {
    }

    public static void show(Context context) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.sheet_share_app, null, false);

        String message = context.getString(R.string.share_app_text, storeUrl(context));
        ((TextView) content.findViewById(R.id.shareMessage)).setText(message);

        BottomSheetDialog sheet = new BottomSheetDialog(context, R.style.ThemeOverlay_Ds_BottomSheet);
        sheet.setContentView(content);

        content.findViewById(R.id.shareMessages).setOnClickListener(v -> {
            sendToMessaging(context, message);
            sheet.dismiss();
        });
        content.findViewById(R.id.shareEmail).setOnClickListener(v -> {
            sendToEmail(context, message);
            sheet.dismiss();
        });
        content.findViewById(R.id.shareMore).setOnClickListener(v -> {
            sendAnywhere(context, message);
            sheet.dismiss();
        });
        content.findViewById(R.id.shareCopy).setOnClickListener(v -> {
            copy(context, message);
            sheet.dismiss();
        });

        sheet.show();
    }

    // ------------------------------------------------------------------ the four ways out

    /**
     * The phone's SMS app, whichever that is.
     *
     * <p>The {@code sms:} scheme rather than a chooser filtered to messaging, because a chooser
     * that offers one app is a dialog asking a question with one answer.
     */
    private static void sendToMessaging(Context context, String message) {
        Intent sms = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))
                .putExtra("sms_body", message);
        if (start(context, sms)) return;
        // No SMS app is entirely normal on a tablet. Said out loud rather than silently doing
        // nothing, and the chooser is offered as the way through.
        Toast.makeText(context, R.string.share_no_messaging_app, Toast.LENGTH_SHORT).show();
        sendAnywhere(context, message);
    }

    private static void sendToEmail(Context context, String message) {
        Intent mail = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name))
                .putExtra(Intent.EXTRA_TEXT, message);
        if (start(context, mail)) return;
        Toast.makeText(context, R.string.about_no_mail_app, Toast.LENGTH_SHORT).show();
        sendAnywhere(context, message);
    }

    private static void sendAnywhere(Context context, String message) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, message);
        if (start(context, Intent.createChooser(send,
                context.getString(R.string.setting_share_app)))) {
            return;
        }
        Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show();
    }

    /**
     * Puts the message on the clipboard.
     *
     * <p>Silent from Android 13, deliberately: the system shows its own copy confirmation there, and
     * a toast on top of it is the same news twice.
     */
    private static void copy(Context context, String message) {
        ClipboardManager clipboard = ContextCompat.getSystemService(context, ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(
                context.getString(R.string.app_name), message));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean start(Context context, Intent intent) {
        try {
            // A sheet can be raised from a non-activity context, and an activity started from one
            // needs its own task or the system refuses it outright.
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private static String storeUrl(Context context) {
        return "https://play.google.com/store/apps/details?id=" + context.getPackageName();
    }
}
