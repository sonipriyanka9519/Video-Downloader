package com.ms.webview.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.ms.webview.R;

/**
 * Settings as a screen of its own — screen 10.
 *
 * <p>A host and nothing more: everything in it is {@link SettingsFragment}, which is also the third
 * tab of the bottom navigation. Two ways in, one list. The alternative was the same sixteen rows
 * written twice, and the second copy is always the one that stops being updated.
 *
 * <p>This is what the browser's overflow opens, where settings is somewhere you go to and come back
 * from — hence the back arrow, which the tab does not have. See {@link SettingsFragment#withBack()}.
 */
public class SettingsActivity extends AppCompatActivity {

    public static void open(Context context) {
        context.startActivity(new Intent(context, SettingsActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Only on a first creation. On a rotation the fragment manager has already restored the
        // one that was there, and committing another would stack a second list on top of it.
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.settingsHost, SettingsFragment.withBack())
                    .commit();
        }
    }
}
