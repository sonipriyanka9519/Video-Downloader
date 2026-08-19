package com.ms.webview.ui.lock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

/**
 * "Prove it, then do it" — the one way anything private is reached.
 *
 * <p>Every private action goes through here: opening the folder, moving a video in, moving several
 * in, turning the lock off from Settings. Each of them is the same three-step question — is there a
 * lock, does the viewer hold it, carry on — and writing that out four times would be four chances
 * for one of them to forget the middle step.
 *
 * <p>Three routes out of {@link #require}:
 * <ul>
 *   <li>No lock set yet — the intro explains what the folder is, then setup, then the action. This
 *       is what makes "make this private" work as a first act, without a trip to Settings first.</li>
 *   <li>Lock set — the challenge screen, then the action.</li>
 *   <li>Declined at either — nothing happens at all, and nothing is said. Backing out of a PIN
 *       prompt is an answer, not an error.</li>
 * </ul>
 *
 * <p>One instance per screen, built while that screen is being created: activity results have to be
 * registered before the screen is started, so this cannot be made on demand inside a click.
 */
public final class PrivateAuth {

    private final Context context;
    private final ActivityResultLauncher<Intent> launcher;

    /**
     * What to do once the viewer has proved who they are. Held rather than passed through the
     * intent because it is a lambda, not data — and cleared as it is taken, so a result that
     * arrives twice cannot run it twice.
     */
    @Nullable
    private Runnable pending;

    public PrivateAuth(@NonNull ActivityResultCaller caller, @NonNull Context context) {
        this.context = context.getApplicationContext();
        this.launcher = caller.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    Runnable action = pending;
                    pending = null;
                    if (result.getResultCode() == Activity.RESULT_OK && action != null) {
                        action.run();
                    }
                });
    }

    /**
     * @param host   an activity context — the intro is a sheet and needs a window to sit in
     * @param reason a string naming what is being unlocked, shown on the challenge
     * @param action what to run once they are through
     */
    public void require(@NonNull Context host, @StringRes int reason, @NonNull Runnable action) {
        pending = action;
        if (AppLock.isEnabled(context)) {
            launcher.launch(AppLockActivity.challenge(context, reason));
            return;
        }
        // No credential yet, so the question is the other one: whether they want this at all. Asked
        // once and then never again — the second time somebody reaches for the private folder they
        // know what it is, and an explanation they have already read standing between them and the
        // keypad is an obstacle rather than a courtesy.
        if (AppLock.introSeen(context)) {
            launcher.launch(AppLockSetupActivity.intent(context));
            return;
        }
        AppLock.markIntroSeen(context);
        PrivateFolderIntro.show(host, () -> launcher.launch(AppLockSetupActivity.intent(context)));
    }

    /** Whether a challenge would be raised at all, for a screen that wants to say so up front. */
    public boolean isLocked() {
        return AppLock.isEnabled(context);
    }
}
