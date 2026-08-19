package com.ms.webview.ui.settings;

import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatDelegate;

import com.ms.webview.R;

/** Light, dark, or whatever the phone is doing — screen 10's Appearance section. */
public enum ThemeChoice {

    SYSTEM(R.string.theme_system, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT(R.string.theme_light, AppCompatDelegate.MODE_NIGHT_NO),
    DARK(R.string.theme_dark, AppCompatDelegate.MODE_NIGHT_YES);

    @StringRes
    public final int label;
    /** The AppCompat constant this maps to, so the mapping lives in one place. */
    public final int mode;

    ThemeChoice(@StringRes int label, int mode) {
        this.label = label;
        this.mode = mode;
    }

    public static ThemeChoice of(String name) {
        if (name != null) {
            for (ThemeChoice option : values()) {
                if (option.name().equals(name)) return option;
            }
        }
        return SYSTEM;
    }
}
