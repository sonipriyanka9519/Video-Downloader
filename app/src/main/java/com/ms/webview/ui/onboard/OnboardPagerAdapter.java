package com.ms.webview.ui.onboard;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;

/** The three introduction pages. */
public class OnboardPagerAdapter
        extends RecyclerView.Adapter<OnboardPagerAdapter.Holder> {

    private static final Page[] PAGES = {
            new Page(R.string.onboard_title_1, R.string.onboard_body_1,
                    R.drawable.il_onboard_browse),
            new Page(R.string.onboard_title_2, R.string.onboard_body_2,
                    R.drawable.il_onboard_detect),
            new Page(R.string.onboard_title_3, R.string.onboard_body_3,
                    R.drawable.il_onboard_download),
    };

    public static int pageCount() {
        return PAGES.length;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.page_onboard, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Page page = PAGES[position];
        h.title.setText(page.title);
        h.body.setText(page.body);
        h.art.setImageResource(page.art);
        // Only the first page carries the mark; repeating it three times would just be noise.
        h.badge.setVisibility(position == 0 ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public int getItemCount() {
        return PAGES.length;
    }

    private static final class Page {
        @StringRes
        final int title;
        @StringRes
        final int body;
        @DrawableRes
        final int art;

        Page(@StringRes int title, @StringRes int body, @DrawableRes int art) {
            this.title = title;
            this.body = body;
            this.art = art;
        }
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ImageView badge;
        final TextView title;
        final TextView body;
        final ImageView art;

        Holder(@NonNull View v) {
            super(v);
            badge = v.findViewById(R.id.onboardBadge);
            title = v.findViewById(R.id.onboardTitle);
            body = v.findViewById(R.id.onboardBody);
            art = v.findViewById(R.id.onboardArt);
        }
    }
}
