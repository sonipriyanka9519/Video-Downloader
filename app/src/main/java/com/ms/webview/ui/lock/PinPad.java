package com.ms.webview.ui.lock;

import android.provider.Settings;
import android.view.View;
import android.view.animation.CycleInterpolator;
import android.view.animation.TranslateAnimation;

import androidx.annotation.NonNull;

import com.ms.webview.R;

/**
 * The keypad and its four dots, wired together — screen 11, panels B, C and F.
 *
 * <p>One class for both screens that have one. Setting a PIN and entering one are the same
 * interaction with different consequences, and duplicating the digit handling would mean two
 * places for a dot to get out of step with what has been typed.
 *
 * <p>The digits live in a StringBuilder that is emptied rather than replaced, so an entered PIN
 * is not left lying around as a String for the garbage collector to get to eventually.
 */
public final class PinPad {

    public interface Listener {
        /** Each keypress, so the screen can clear whatever error it was showing. */
        void onPinChanged(int length);

        /** Four digits are in. */
        void onPinEntered(String pin);
    }

    private static final long SHAKE_MS = 400L;
    private static final int SHAKE_CYCLES = 4;
    private static final float SHAKE_DISTANCE = 16f;

    private final Listener listener;
    private final View dots;
    private final View[] dot = new View[AppLock.PIN_LENGTH];
    private final StringBuilder entered = new StringBuilder(AppLock.PIN_LENGTH);
    /** True from a mismatch until the next keypress, which is what clears it. */
    private boolean error;

    public PinPad(@NonNull View root, @NonNull Listener listener) {
        this.listener = listener;
        dots = root.findViewById(R.id.pinDots);
        dot[0] = root.findViewById(R.id.pinDot1);
        dot[1] = root.findViewById(R.id.pinDot2);
        dot[2] = root.findViewById(R.id.pinDot3);
        dot[3] = root.findViewById(R.id.pinDot4);

        int[] keys = {R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
                R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9};
        for (int digit = 0; digit < keys.length; digit++) {
            final int value = digit;
            root.findViewById(keys[digit]).setOnClickListener(v -> press(value));
        }
        root.findViewById(R.id.keyBackspace).setOnClickListener(v -> backspace());
        redraw();
    }

    private void press(int digit) {
        if (entered.length() >= AppLock.PIN_LENGTH) return;

        entered.append(digit);
        // Any keypress leaves the error behind: the viewer is answering it by retyping.
        error = false;
        redraw();
        listener.onPinChanged(entered.length());

        if (entered.length() == AppLock.PIN_LENGTH) listener.onPinEntered(entered.toString());
    }

    private void backspace() {
        if (entered.length() == 0) return;
        entered.deleteCharAt(entered.length() - 1);
        error = false;
        redraw();
        listener.onPinChanged(entered.length());
    }

    public void clear() {
        entered.setLength(0);
        error = false;
        redraw();
    }

    /**
     * Panel F: the dots turn to outlines in error and the row shakes.
     *
     * <p>Both together, deliberately. The shake alone is missed by anyone not looking at that
     * part of the screen; the colour alone is missed by anyone who cannot distinguish it. Either
     * on its own leaves somebody retyping a PIN with no idea why nothing happened.
     */
    public void showMismatch() {
        entered.setLength(0);
        error = true;
        redraw();
        shake();
    }

    /**
     * Respects the animator duration scale.
     *
     * <p>At zero the system is saying somebody finds motion uncomfortable, and a screen that
     * shakes at them anyway is the worst possible place to ignore that. The colour still carries
     * the message on its own.
     */
    private void shake() {
        float scale = Settings.Global.getFloat(dots.getContext().getContentResolver(),
                Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        if (scale == 0f) return;

        TranslateAnimation shake = new TranslateAnimation(0, SHAKE_DISTANCE, 0, 0);
        shake.setDuration(SHAKE_MS);
        shake.setInterpolator(new CycleInterpolator(SHAKE_CYCLES));
        dots.startAnimation(shake);
    }

    /**
     * Empty, entered, or wrong — three states, so three cases here.
     *
     * <p>Every dot is set on every pass. A dot left over from the previous state is exactly the
     * kind of thing that leaves four red outlines on screen after the PIN has been retyped.
     */
    private void redraw() {
        for (int i = 0; i < dot.length; i++) {
            if (error) {
                // state_active is the error state in ds_pin_dot. Set directly because there is
                // no View setter for it — activated and selected already mean other things.
                dot[i].setActivated(false);
                dot[i].getBackground().setState(new int[]{android.R.attr.state_active});
            } else {
                dot[i].setActivated(i < entered.length());
                // Pushed to the drawable by hand, and this is the whole of the fix for dots that
                // emptied as the next digit was typed. setActivated only refreshes the drawable
                // when the flag actually changes, so a dot that was already filled kept whatever
                // state had last been forced on it — including the cleared one this used to set
                // a line earlier. Recomputing from the view's own state restores the fill and
                // clears the error state in one move.
                dot[i].refreshDrawableState();
            }
        }
    }
}
