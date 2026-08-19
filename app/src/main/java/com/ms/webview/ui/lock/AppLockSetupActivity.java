package com.ms.webview.ui.lock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;

import com.ms.webview.R;
import com.ms.webview.ui.SystemBars;

import java.util.Arrays;
import java.util.List;

/**
 * Turning the lock on — screen 11, panels A, B and F.
 *
 * <p>Four states in one screen rather than four activities: choose a method, enter a PIN,
 * confirm it, answer a recovery question. Back walks them in reverse, which is what makes a
 * mistyped PIN a step back rather than a restart.
 *
 * <p>A PIN is always set, whichever method is chosen. The design is explicit and it is right: a
 * fingerprint can fail — a wet hand, a cut, a phone that has forgotten the sensor — and a lock
 * with no second way in is a lock that will eventually shut its owner out.
 */
public class AppLockSetupActivity extends AppCompatActivity implements PinPad.Listener {

    private enum Step { METHOD, CREATE, CONFIRM, RECOVERY }

    private View methodStep;
    private View pinStep;
    private View recoveryStep;
    private TextView heading;
    private TextView pinTitle;
    private TextView pinBody;
    private TextView pinError;
    private TextView recoveryQuestion;
    private EditText recoveryAnswer;
    private TextView recoveryError;

    private PinPad pad;
    private Step step = Step.METHOD;
    private boolean useBiometric = true;

    /** Held between CREATE and CONFIRM, and cleared the moment it is no longer needed. */
    @Nullable
    private String firstEntry;
    private String chosenQuestion;

    public static void open(Context context) {
        context.startActivity(intent(context));
    }

    /**
     * For a caller that has something waiting on the other side of setup — see {@link PrivateAuth}.
     * Finishing with a lock in place returns {@link Activity#RESULT_OK}; backing out returns
     * nothing, and whatever was waiting does not happen.
     */
    public static Intent intent(Context context) {
        return new Intent(context, AppLockSetupActivity.class);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_lock_setup);

        methodStep = findViewById(R.id.methodStep);
        pinStep = findViewById(R.id.pinStep);
        recoveryStep = findViewById(R.id.recoveryStep);
        heading = findViewById(R.id.lockHeading);
        pinTitle = findViewById(R.id.pinTitle);
        pinBody = findViewById(R.id.pinBody);
        pinError = findViewById(R.id.pinError);
        recoveryQuestion = findViewById(R.id.recoveryQuestion);
        recoveryAnswer = findViewById(R.id.recoveryAnswer);
        recoveryError = findViewById(R.id.recoveryError);

        pad = new PinPad(findViewById(R.id.pinStep), this);
        SystemBars.pad(findViewById(R.id.lockSetupRoot));
        setUpMethodStep();
        setUpRecoveryStep();

        findViewById(R.id.btnLockBack).setOnClickListener(v -> goBack());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                goBack();
            }
        });

        show(Step.METHOD);
    }

    // ------------------------------------------------------------------ method

    private void setUpMethodStep() {
        View biometric = findViewById(R.id.methodBiometric);
        View pin = findViewById(R.id.methodPin);

        // Offered only when the phone can actually do it. A card that opens a sheet saying "no
        // fingerprints enrolled" is a card that should not have been there.
        boolean available = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS;
        biometric.setVisibility(available ? View.VISIBLE : View.GONE);
        useBiometric = available;

        biometric.setActivated(true);
        pin.setActivated(!available);

        biometric.setOnClickListener(v -> {
            useBiometric = true;
            biometric.setActivated(true);
            pin.setActivated(false);
            show(Step.CREATE);
        });
        pin.setOnClickListener(v -> {
            useBiometric = false;
            biometric.setActivated(false);
            pin.setActivated(true);
            show(Step.CREATE);
        });
    }

    // ------------------------------------------------------------------ the PIN

    @Override
    public void onPinChanged(int length) {
        // Any keypress clears the mismatch. Leaving it up while somebody is retyping makes the
        // screen argue with what they are currently doing.
        if (length > 0) pinError.setVisibility(View.INVISIBLE);
    }

    @Override
    public void onPinEntered(String pin) {
        if (step == Step.CREATE) {
            firstEntry = pin;
            pad.clear();
            show(Step.CONFIRM);
            return;
        }

        if (pin.equals(firstEntry)) {
            show(Step.RECOVERY);
            return;
        }

        // Panel F: outlines in error, one plain sentence, and a shake. No lockout copy — four
        // digits typed twice is a typo, not an attack.
        pad.showMismatch();
        pinError.setText(R.string.lock_pin_mismatch);
        pinError.setVisibility(View.VISIBLE);
        firstEntry = null;
        show(Step.CREATE);
    }

    // ------------------------------------------------------------------ recovery

    private void setUpRecoveryStep() {
        List<String> questions = Arrays.asList(getResources()
                .getStringArray(R.array.lock_recovery_questions));
        chosenQuestion = questions.get(0);
        recoveryQuestion.setText(chosenQuestion);

        recoveryQuestion.setOnClickListener(v -> new com.google.android.material.dialog
                .MaterialAlertDialogBuilder(this, R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.lock_recovery_pick)
                .setItems(questions.toArray(new String[0]), (dialog, which) -> {
                    chosenQuestion = questions.get(which);
                    recoveryQuestion.setText(chosenQuestion);
                })
                .show());

        findViewById(R.id.btnLockFinish).setOnClickListener(v -> finishSetup());
    }

    private void finishSetup() {
        String answer = recoveryAnswer.getText() == null
                ? "" : recoveryAnswer.getText().toString().trim();
        if (answer.isEmpty()) {
            recoveryError.setText(R.string.lock_recovery_required);
            recoveryError.setVisibility(View.VISIBLE);
            return;
        }
        if (TextUtils.isEmpty(firstEntry)) {
            // Cannot happen from the UI, but the alternative is enabling a lock with no PIN.
            show(Step.CREATE);
            return;
        }

        AppLock.enable(this, firstEntry, chosenQuestion, answer);
        AppLock.setBiometricPreferred(this, useBiometric);
        // Gone from memory the moment it is stored. It only ever existed to be compared against.
        firstEntry = null;
        // The lock is in place, so whatever was waiting on it may go ahead — the video that was
        // being made private, or the folder that was being opened.
        setResult(RESULT_OK);
        finish();
    }

    // ------------------------------------------------------------------ steps

    private void show(Step next) {
        step = next;
        methodStep.setVisibility(next == Step.METHOD ? View.VISIBLE : View.GONE);
        pinStep.setVisibility(next == Step.CREATE || next == Step.CONFIRM
                ? View.VISIBLE : View.GONE);
        recoveryStep.setVisibility(next == Step.RECOVERY ? View.VISIBLE : View.GONE);

        switch (next) {
            case METHOD:
                heading.setText(R.string.setting_app_lock);
                break;
            case CREATE:
                heading.setText(getString(R.string.lock_step, 1, 3));
                pinTitle.setText(R.string.lock_create_pin);
                pinBody.setText(R.string.lock_create_pin_body);
                pad.clear();
                break;
            case CONFIRM:
                heading.setText(getString(R.string.lock_step, 2, 3));
                pinTitle.setText(R.string.lock_confirm_pin);
                pinBody.setText(R.string.lock_confirm_pin_body);
                pinError.setVisibility(View.INVISIBLE);
                pad.clear();
                break;
            case RECOVERY:
                heading.setText(getString(R.string.lock_step, 3, 3));
                break;
        }
    }

    /** Back walks the steps in reverse, and only leaves from the first one. */
    private void goBack() {
        switch (step) {
            case CONFIRM:
                firstEntry = null;
                show(Step.CREATE);
                break;
            case RECOVERY:
                show(Step.CONFIRM);
                break;
            case CREATE:
                show(Step.METHOD);
                break;
            default:
                finish();
                break;
        }
    }
}
