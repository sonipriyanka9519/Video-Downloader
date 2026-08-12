package com.ms.webview.ui;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

/** The two top-level tabs. */
public class MainPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_HOME = 0;
    public static final int PAGE_DOWNLOADS = 1;

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
        return position == PAGE_DOWNLOADS ? new DownloadsFragment() : new BrowserFragment();
    }

    @Override
    public int getItemCount() {
        return 2;
    }
}
