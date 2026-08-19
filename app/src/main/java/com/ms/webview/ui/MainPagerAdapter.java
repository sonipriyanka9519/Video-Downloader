package com.ms.webview.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import com.ms.webview.ui.settings.SettingsFragment;

/** The three top-level tabs. */
public class MainPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_HOME = 0;
    public static final int PAGE_DOWNLOADS = 1;
    /**
     * Settings, as a tab as well as a screen.
     *
     * <p>The same fragment SettingsActivity hosts. The browser's overflow still opens it as a
     * screen, because from a page that is where somebody expects to be taken and to come back from.
     */
    public static final int PAGE_SETTINGS = 2;

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        switch (position) {
            case PAGE_DOWNLOADS:
                return new DownloadsFragment();
            case PAGE_SETTINGS:
                // No back arrow on a tab: there is nothing behind it to go back to.
                return new SettingsFragment();
            default:
                return new BrowserFragment();
        }
    }

    @Override
    public int getItemCount() {
        return 3;
    }
}
