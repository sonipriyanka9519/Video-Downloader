package com.ms.webview.ui.lock;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * The credential that guards the private folder — screen 11.
 *
 * <p><b>It does not guard the app.</b> There is no lock screen on open and no gate in front of the
 * library: a downloader that demanded a PIN before it would show a list of files would be asking
 * for one every time somebody wanted to watch something. What is worth protecting is the handful
 * of videos the viewer marked private, and those are what this asks about — opening the private
 * folder, moving a video into it, and turning the lock off again.
 *
 * <p>One credential for all three, deliberately: a separate PIN per surface would be more things
 * to forget and more things to reset, for no more privacy than one.
 *
 * <p><b>What this is and is not.</b> A four-digit PIN has ten thousand possibilities, so anyone
 * who can read this file and run code against it will get in. This is a privacy screen — it
 * stops the person handed your unlocked phone, not someone with your unlocked phone and a
 * debugger. Saying so here rather than letting a future reader assume otherwise.
 *
 * <p>What it does do properly: the PIN is never stored, only a PBKDF2 hash of it with a random
 * per-install salt, so the file cannot be read off and the number recognised. The same goes for
 * the recovery answer. Neither is ever logged.
 */
public final class AppLock {

    private static final String TAG = "AppLock";
    private static final String PREFS = "app_lock";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PIN = "pin";
    private static final String KEY_PIN_SALT = "pin_salt";
    private static final String KEY_BIOMETRIC = "biometric";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_ANSWER = "answer";
    private static final String KEY_ANSWER_SALT = "answer_salt";
    private static final String KEY_INTRO_SEEN = "intro_seen";
    /**
     * How many rounds each credential was hashed with, recorded per credential.
     *
     * <p>Per credential and not one shared key, which an earlier version had and which would have
     * been a lockout waiting to happen: re-hashing the PIN at a new cost would have moved the count
     * the recovery answer is checked against too, and the answer would never have matched again.
     */
    private static final String KEY_PIN_ITERATIONS = "pin_iterations";
    private static final String KEY_ANSWER_ITERATIONS = "answer_iterations";

    /** What the shared key was called before it was split. Read once, on the way past. */
    private static final String KEY_ITERATIONS = "iterations";

    public static final int PIN_LENGTH = 4;

    /**
     * Deliberately slow, but no slower than it has to be.
     *
     * <p>Ten thousand PINs is nothing to try, so the only lever left is making each attempt cost
     * something. At this count a full sweep of every four-digit PIN is still tens of millions of
     * hash rounds — minutes of dedicated work against a file somebody has already had to extract
     * from the device — while a single check is quick enough that the fourth digit does not feel
     * like a pause.
     *
     * <p>It was three times this, which bought a longer sweep at the price of a wait on every
     * unlock, several times a day, for the one person who is not attacking anything.
     */
    private static final int ITERATIONS = 20_000;

    /**
     * What credentials stored before any count was recorded were hashed with.
     *
     * <p>A hash cannot be re-derived without the plaintext, so an old credential has to be checked
     * the way it was made — once. On the first correct answer it is rebuilt at the current cost
     * and this stops applying to it; see {@link #upgrade}. Without that step the reduction reached
     * new installs only, and everybody who already had a PIN kept paying the old price forever.
     *
     * <p>Getting this value wrong would lock out every existing PIN, which is why it is written
     * down rather than assumed.
     */
    private static final int LEGACY_ITERATIONS = 60_000;
    private static final int KEY_BITS = 256;

    private AppLock() {
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false)
                && !TextUtils.isEmpty(prefs(context).getString(KEY_PIN, null));
    }

    /**
     * Whether the private-folder intro has already been shown — screen 11, panel D.
     *
     * <p>Kept here, in the lock's own store, so that turning the lock off forgets it along with
     * everything else: somebody setting the folder up a second time months later is being told
     * about it for the first time again.
     */
    public static boolean introSeen(Context context) {
        return prefs(context).getBoolean(KEY_INTRO_SEEN, false);
    }

    public static void markIntroSeen(Context context) {
        prefs(context).edit().putBoolean(KEY_INTRO_SEEN, true).apply();
    }

    /** Whether the viewer asked for the system biometric sheet as well as the PIN. */
    public static boolean biometricPreferred(Context context) {
        return prefs(context).getBoolean(KEY_BIOMETRIC, false);
    }

    public static void setBiometricPreferred(Context context, boolean value) {
        prefs(context).edit().putBoolean(KEY_BIOMETRIC, value).apply();
    }

    /**
     * Turns the lock on with a new PIN and its recovery answer.
     *
     * <p>All three at once rather than one at a time: a lock with a PIN and no way back in is a
     * lock somebody will eventually be shut out by, and the design makes the recovery question
     * step 3 of setup for exactly that reason.
     */
    public static void enable(Context context, String pin, String question, String answer) {
        byte[] pinSalt = salt();
        byte[] answerSalt = salt();

        prefs(context).edit()
                .putBoolean(KEY_ENABLED, true)
                // Recorded with the credential, so a future change of cost cannot invalidate it.
                .putInt(KEY_PIN_ITERATIONS, ITERATIONS)
                .putInt(KEY_ANSWER_ITERATIONS, ITERATIONS)
                .putString(KEY_PIN, hash(pin, pinSalt))
                .putString(KEY_PIN_SALT, encode(pinSalt))
                .putString(KEY_QUESTION, question)
                .putString(KEY_ANSWER, hash(normalise(answer), answerSalt))
                .putString(KEY_ANSWER_SALT, encode(answerSalt))
                .apply();
    }

    /** Turns it off and forgets everything it held. */
    public static void disable(Context context) {
        prefs(context).edit().clear().apply();
    }

    public static boolean checkPin(Context context, String pin) {
        return matches(context, pin, KEY_PIN, KEY_PIN_SALT, KEY_PIN_ITERATIONS);
    }

    /**
     * The only route back in, and it never unlocks by email — the design is explicit.
     *
     * <p>Compared case- and space-insensitively: somebody typing their mother's maiden name a
     * year later should not be locked out by a capital letter.
     */
    public static boolean checkAnswer(Context context, String answer) {
        return matches(context, normalise(answer), KEY_ANSWER, KEY_ANSWER_SALT,
                KEY_ANSWER_ITERATIONS);
    }

    @Nullable
    public static String question(Context context) {
        return prefs(context).getString(KEY_QUESTION, null);
    }

    // ------------------------------------------------------------------ hashing

    private static boolean matches(Context context, String value, String key, String saltKey,
                                   String roundsKey) {
        String stored = prefs(context).getString(key, null);
        String salt = prefs(context).getString(saltKey, null);
        if (TextUtils.isEmpty(stored) || TextUtils.isEmpty(salt) || value == null) return false;

        int rounds = roundsFor(context, roundsKey);
        String candidate = hash(value, decode(salt), rounds);
        // Constant-time, so a timing difference cannot leak how much of the value was right.
        if (candidate == null || !constantTimeEquals(candidate, stored)) return false;

        // Right answer, wrong cost. Re-made at the current one while the plaintext is in hand —
        // the only moment it ever is.
        if (rounds != ITERATIONS) upgrade(context, value, key, saltKey, roundsKey);
        return true;
    }

    /**
     * The count this credential was actually made with.
     *
     * <p>Three answers in order of how much they are trusted: its own key; the shared key an
     * earlier build wrote; and failing both, {@link #LEGACY_ITERATIONS}, because a credential
     * stored before any of this was recorded was made at the original cost.
     */
    private static int roundsFor(Context context, String roundsKey) {
        SharedPreferences prefs = prefs(context);
        if (prefs.contains(roundsKey)) return prefs.getInt(roundsKey, ITERATIONS);
        return prefs.getInt(KEY_ITERATIONS, LEGACY_ITERATIONS);
    }

    /**
     * Re-hashes a credential at the current cost, having just seen it proved.
     *
     * <p>This is why an old PIN is slow exactly once. The cost was lowered, but a hash cannot be
     * re-derived without the plaintext, so an already-enrolled PIN went on being checked at the old
     * count forever — the reduction reached new installs and nobody else. Verifying is the one
     * moment the plaintext exists, so that is when it is rebuilt.
     *
     * <p>A fresh salt with it. Reusing the old one would be safe enough, but a new one costs
     * nothing here and means no two hashes of this credential ever share a salt.
     *
     * <p>Runs on whatever thread called in, which is always a background one — see AppLockActivity
     * and PrivateAuth. Written as one commit so a kill mid-way cannot leave the hash and its count
     * disagreeing.
     */
    private static void upgrade(Context context, String value, String key, String saltKey,
                                String roundsKey) {
        byte[] salt = salt();
        String rehashed = hash(value, salt, ITERATIONS);
        // Nothing written on a failed hash: the credential that is stored still works.
        if (rehashed == null) return;

        prefs(context).edit()
                .putString(key, rehashed)
                .putString(saltKey, encode(salt))
                .putInt(roundsKey, ITERATIONS)
                .apply();
    }

    @Nullable
    private static String hash(String value, byte[] salt) {
        return hash(value, salt, ITERATIONS);
    }

    @Nullable
    private static String hash(String value, byte[] salt, int rounds) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    value.toCharArray(), salt, rounds, KEY_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
            return encode(factory.generateSecret(spec).getEncoded());
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            // Never with the value in it. A log line carrying the PIN would undo the point of
            // hashing it in the first place.
            Log.e(TAG, "Cannot hash credential", e);
            return null;
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static byte[] salt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String encode(byte[] bytes) {
        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }

    private static byte[] decode(String value) {
        return Base64.decode(value, Base64.NO_WRAP);
    }

    private static String normalise(String answer) {
        return answer == null ? "" : answer.trim().toLowerCase(Locale.ROOT);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
