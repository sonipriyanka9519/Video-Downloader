package com.ms.webview.ui.guide;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.ms.webview.MainActivity;
import com.ms.webview.R;
import com.ms.webview.ui.SystemBars;

/**
 * Screen 14 — the walkthrough.
 *
 * <p>Four pages teaching one loop: open a site, play a video, tap the button, pick a quality. The
 * same four pages serve first run and every later visit from Settings or the downloads screen, which
 * is the point of them being here rather than in a help article — there is one explanation of the
 * app and it is this one.
 *
 * <p>Every diagram is abstract by instruction, and the reason is worth keeping in view: a page that
 * looks like the real interface invites somebody to operate it, and they will tap an illustration and
 * conclude the app is broken. Nothing on these pages is tappable except the button in the footer.
 */
public class WalkthroughActivity extends AppCompatActivity {

    /**
     * Set when this is the first launch rather than a later visit.
     *
     * <p>It decides where finishing goes: on first run the app has not opened yet and this screen
     * has to open it, and afterwards it is simply on top of whatever asked for it and can get out of
     * the way. Without the distinction, re-reading the walkthrough from Settings would drop somebody
     * back onto the home screen.
     */
    private static final String EXTRA_FIRST_RUN = "first_run";

    /** The pages, in order. Each is a step chip, a heading, a sentence, a diagram and a caption. */
    private enum Page {
        OPEN(R.string.walk_open_title, R.string.walk_open_body, R.string.walk_open_caption,
                R.layout.art_walk_open),
        PLAY(R.string.walk_play_title, R.string.walk_play_body, R.string.walk_play_caption,
                R.layout.art_walk_play),
        BUTTON(R.string.walk_button_title, R.string.walk_button_body, R.string.walk_button_caption,
                R.layout.art_walk_button),
        QUALITY(R.string.walk_quality_title, R.string.walk_quality_body,
                R.string.walk_quality_caption, R.layout.art_walk_quality);

        @StringRes
        final int title;
        @StringRes
        final int body;
        @StringRes
        final int caption;
        @LayoutRes
        final int art;

        Page(@StringRes int title, @StringRes int body, @StringRes int caption, @LayoutRes int art) {
            this.title = title;
            this.body = body;
            this.caption = caption;
            this.art = art;
        }
    }

    private ViewPager2 pager;
    private LinearLayout dots;
    private MaterialButton next;
    private View skip;

    /** From the splash, on a first launch. */
    public static Intent firstRun(Context context) {
        return new Intent(context, WalkthroughActivity.class)
                .putExtra(EXTRA_FIRST_RUN, true);
    }

    /** From Settings, the downloads empty state, or the browser's overflow. */
    public static void open(Context context) {
        context.startActivity(new Intent(context, WalkthroughActivity.class));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_walkthrough);

        pager = findViewById(R.id.walkPager);
        dots = findViewById(R.id.walkDots);
        next = findViewById(R.id.btnWalkNext);
        skip = findViewById(R.id.btnWalkSkip);

        SystemBars.pad(findViewById(R.id.walkRoot));

        pager.setAdapter(new PageAdapter());
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                bindFooter(position);
            }
        });

        buildDots();
        bindFooter(0);

        next.setOnClickListener(v -> advance());
        skip.setOnClickListener(v -> finishWalkthrough());

        // Reaching the walkthrough at all is what the offer was for. Recorded here rather than on
        // the last page, so somebody who reads two pages and leaves is not asked again — see HowTo.
        HowTo.markSeen(this);
    }

    private void buildDots() {
        dots.removeAllViews();
        for (int i = 0; i < Page.values().length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    getResources().getDimensionPixelSize(R.dimen.ds_walk_dot),
                    getResources().getDimensionPixelSize(R.dimen.ds_walk_dot));
            if (i > 0) {
                params.setMarginStart(getResources().getDimensionPixelSize(R.dimen.ds_walk_dot_gap));
            }
            dot.setLayoutParams(params);
            dots.addView(dot);
        }
    }

    /**
     * The dots, the button's words, and whether Skip is there at all.
     *
     * <p>The active dot is a pill rather than a larger circle, which is the same treatment a selected
     * chip gets — one language for "this is the one you are on".
     */
    private void bindFooter(int position) {
        int size = getResources().getDimensionPixelSize(R.dimen.ds_walk_dot);
        int wide = getResources().getDimensionPixelSize(R.dimen.ds_walk_dot_active);

        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            boolean active = i == position;
            ViewGroup.LayoutParams params = dot.getLayoutParams();
            params.width = active ? wide : size;
            params.height = size;
            dot.setLayoutParams(params);
            dot.setBackgroundResource(active
                    ? R.drawable.ds_bg_walk_dot_active : R.drawable.ds_bg_walk_dot);
        }

        boolean last = position == Page.values().length - 1;
        next.setText(last ? R.string.walk_start : R.string.walk_next);
        // Off the last page: the primary button already ends the flow there, and a second way out
        // beside it is one more decision than the page is asking for. Invisible rather than gone,
        // so it cannot be tapped and the row above the pages keeps exactly the same height.
        skip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
    }

    private void advance() {
        int at = pager.getCurrentItem();
        if (at < Page.values().length - 1) {
            pager.setCurrentItem(at + 1, true);
            return;
        }
        finishWalkthrough();
    }

    /**
     * Out of the walkthrough, and into the app if that is what is behind it.
     *
     * <p>On a first launch nothing is open yet, so this opens it. Any other time the app is already
     * there and finishing is enough — starting a second copy of the home screen would put the tab
     * they were on behind a fresh one.
     */
    private void finishWalkthrough() {
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_FIRST_RUN, false)) {
            Intent home = new Intent(this, MainActivity.class);
            // Whatever the launch arrived with — a shared link, a notification payload — travels on
            // through. See SplashActivity.
            if (getIntent().getExtras() != null) home.putExtras(getIntent().getExtras());
            home.removeExtra(EXTRA_FIRST_RUN);
            startActivity(home);
        }
        finish();
    }

    // ------------------------------------------------------------------ the pages

    private class PageAdapter extends RecyclerView.Adapter<PageHolder> {

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PageHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.page_walkthrough, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder h, int position) {
            Page page = Page.values()[position];

            h.step.setText(getString(R.string.walk_step, position + 1, Page.values().length));
            h.title.setText(page.title);
            h.body.setText(page.body);
            h.caption.setText(page.caption);

            // Replaced rather than added to: a recycled page holding the previous diagram as well as
            // its own would stack two of them.
            h.art.removeAllViews();
            LayoutInflater.from(h.art.getContext()).inflate(page.art, h.art, true);
        }

        @Override
        public int getItemCount() {
            return Page.values().length;
        }
    }

    static class PageHolder extends RecyclerView.ViewHolder {
        final TextView step;
        final TextView title;
        final TextView body;
        final TextView caption;
        final ViewGroup art;

        PageHolder(@NonNull View v) {
            super(v);
            step = v.findViewById(R.id.walkStep);
            title = v.findViewById(R.id.walkTitle);
            body = v.findViewById(R.id.walkBody);
            caption = v.findViewById(R.id.walkCaption);
            art = v.findViewById(R.id.walkArt);
        }
    }
}
