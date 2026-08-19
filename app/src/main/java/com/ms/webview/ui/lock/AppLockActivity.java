package com.ms.webview.ui.lock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.R;
import com.ms.webview.ui.SystemBars;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The challenge — screen 11, panel C.
 *
 * <p>Raised in front of one action rather than in front of the app: opening the private folder,
 * moving a video into it, turning the lock off. It answers a question and returns; it does not
 * stand between the viewer and the app. So back cancels it, unlike a gate — deciding not to open
 * the private folder after all is a perfectly ordinary thing to do, and a screen with no way out
 * of it would be the app holding the phone hostage over a folder.
 *
 * <p>Three ways past: the sensor, the PIN, and the recovery question. The biometric prompt is the
 * system's own sheet, which matters beyond looking right — this app never sees a fingerprint, only
 * whether the system was satisfied by one.
 *
 * <p>Launched for a result. {@link Activity#RESULT_OK} means the viewer proved who they are and the
 * caller may go ahead; anything else means they did not. See {@link PrivateAuth}, which is what
 * every caller actually uses.
 */
public class AppLockActivity extends AppCompatActivity implements PinPad.Listener {

    /** What the viewer is being asked for, so the subtitle can say it. */
    private static final String EXTRA_REASON = "reason";

    /** Hashing is slow by design, so it never runs on the thread drawing the keypad. */
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private PinPad pad;
    private TextView error;
    private TextView subtitle;
    private View sensor;

    /**
     * @param reason a string resource naming what is being unlocked, or 0 for the plain wording
     */
    public static Intent challenge(Context context, @StringRes int reason) {
        return new Intent(context, AppLockActivity.class).putExtra(EXTRA_REASON, reason);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Nothing to ask about. Reached only if the lock was turned off from elsewhere while this
        // was opening — the caller wanted the action to happen, and there is now nothing standing
        // in its way, so it is allowed rather than refused.
        if (!AppLock.isEnabled(this)) {
            setResult(RESULT_OK);
            finish();
            return;
        }

        setContentView(R.layout.activity_app_lock);
        error = findViewById(R.id.lockError);
        subtitle = findViewById(R.id.lockSubtitle);
        sensor = findViewById(R.id.btnBiometric);
        pad = new PinPad(findViewById(R.id.lockRoot), this);
        // Clear of the status bar and the gesture area — the app is drawn edge to edge.
        SystemBars.pad(findViewById(R.id.lockRoot));

        findViewById(R.id.btnForgotPin).setOnClickListener(v -> askRecovery());
        sensor.setOnClickListener(v -> promptBiometric());

        boolean biometric = AppLock.biometricPreferred(this) && biometricAvailable();
        sensor.setVisibility(biometric ? View.VISIBLE : View.GONE);

        // The reason first, when there is one: "Unlock to open your private folder" tells the
        // viewer which of the three things they are being asked about, and a PIN prompt with no
        // stated cause is one people answer without knowing what they agreed to.
        int reason = getIntent().getIntExtra(EXTRA_REASON, 0);
        if (reason != 0) {
            subtitle.setText(reason);
        } else {
            subtitle.setText(biometric ? R.string.lock_unlock_biometric : R.string.lock_unlock_pin);
        }

        if (biometric) promptBiometric();
    }

    private boolean biometricAvailable() {
        return BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * The system sheet.
     *
     * <p>Failure is not an error here. A finger that does not read is somebody who can still type
     * the PIN, and the keypad is already on screen underneath — so a dismissed prompt says nothing
     * and simply leaves them to it.
     */
    private void promptBiometric() {
        BiometricPrompt prompt = new BiometricPrompt(this,
                ContextCompat.getMainExecutor(this),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        allow();
                    }
                });

        prompt.authenticate(new BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.lock_unlock_title))
                .setSubtitle(subtitle.getText().toString())
                .setNegativeButtonText(getString(R.string.lock_use_pin))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                .build());
    }

    // ------------------------------------------------------------------ the PIN

    @Override
    public void onPinChanged(int length) {
        if (length > 0) error.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onPinEntered(String pin) {
        // Off the main thread, and that is the whole of why the fourth digit used to hang.
        //
        // Checking a PIN is deliberately slow — PBKDF2, see AppLock.ITERATIONS — because that is
        // what makes ten thousand possible PINs expensive to try. Slow work is fine; slow work on
        // the thread drawing the screen is not, and it froze the keypad for the length of the hash
        // before anything could happen. It is now off that thread, and cheaper besides.
        io.execute(() -> {
            boolean ok = AppLock.checkPin(this, pin);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (ok) {
                    allow();
                    return;
                }
                // No count, no lockout, no escalating delay. This is a privacy screen: the threat
                // is the person handed the phone, and telling them how many tries they have left is
                // telling them there is a game to play.
                pad.showMismatch();
                error.setText(R.string.lock_wrong_pin);
                error.setVisibility(View.VISIBLE);
            });
        });
    }

    private void allow() {
        setResult(RESULT_OK);
        finish();
    }

    /**
     * The way back in — the recovery question, answered here.
     *
     * <p>Answering it correctly turns the lock off rather than revealing the PIN, because the PIN
     * is not stored and cannot be revealed. The viewer is then free to set a new one.
     *
     * <p>It also lets the action through, which is the point of having asked: somebody who has
     * just proved they own the phone wanted to reach their private folder. Until they set a new
     * PIN the folder is not protected, which is why Settings shows the lock as off straight away
     * rather than quietly leaving it looking on.
     */
    private void askRecovery() {
        String question = AppLock.question(this);
        if (question == null) return;

        EditText input = new EditText(this);
        input.setHint(R.string.lock_recovery_hint);
        input.setSingleLine(true);

        new MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(question)
                .setView(input, dp(24), dp(8), dp(24), 0)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.lock_recovery_unlock, (dialog, which) -> {
                    String answer = input.getText() == null ? "" : input.getText().toString();
                    // Off the main thread, exactly as the PIN is. Checking the answer is the same
                    // deliberately-slow hash, and it was being run on the thread drawing the
                    // dialog — the one path where that had been left in place.
                    io.execute(() -> {
                        boolean ok = AppLock.checkAnswer(this, answer);
                        main.post(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            if (!ok) {
                                error.setText(R.string.lock_recovery_wrong);
                                error.setVisibility(View.VISIBLE);
                                return;
                            }
                            // Off entirely, not merely open: somebody who needed the recovery
                            // question has forgotten the PIN, and leaving it set would strand
                            // them again tomorrow.
                            AppLock.disable(this);
                            allow();
                        });
                    });
                })
                .show();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
