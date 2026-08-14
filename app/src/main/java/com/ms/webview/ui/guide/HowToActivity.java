package com.ms.webview.ui.guide;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.ms.webview.R;

/**
 * How to download, in four steps.
 *
 * <p>Reached from the button under the shortcuts, which goes once this has been opened; from the
 * one under the downloads list, which does not; and from the browser's overflow, which is where
 * the first of those goes when it disappears.
 */
public class HowToActivity extends AppCompatActivity {

    private ViewPager2 pager;
    private TextView next;

    /**
     * The four steps, in the order they happen.
     *
     * <p>The pictures are placeholders and are meant to be replaced with real screenshots of these
     * four moments — the file names are the only thing that has to stay.
     */
    private static final HowStep[] STEPS = {
            new HowStep(R.string.how_step_1, R.string.how_step_1_accent, R.drawable.img_how_1),
            new HowStep(R.string.how_step_2, R.string.how_step_2_accent, R.drawable.img_how_2),
            new HowStep(R.string.how_step_3, R.string.how_step_3_accent, R.drawable.img_how_3),
            new HowStep(R.string.how_step_4, R.string.how_step_4_accent, R.drawable.img_how_4),
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_how_to);

        // Recorded on arrival, not on finishing. Reaching this screen is what the button under the
        // shortcuts was for, and someone who reads one step and leaves has answered it. Only the
        // browser acts on this; the downloads screen keeps its own offer either way.
        HowTo.markSeen(this);

        applySystemBarInsets();
        bindPager();

        findViewById(R.id.btnHowClose).setOnClickListener(v -> finish());
        next.setOnClickListener(v -> advance());
    }

    private void bindPager() {
        pager = findViewById(R.id.howPager);
        next = findViewById(R.id.btnHowNext);
        LinearLayout dots = findViewById(R.id.howDots);

        pager.setAdapter(new HowStepAdapter(STEPS));

        int size = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._6sdp);
        int gap = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._4sdp);

        dots.removeAllViews();
        for (int i = 0; i < STEPS.length; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMarginStart(i == 0 ? 0 : gap);
            dot.setLayoutParams(params);
            dots.addView(dot);
        }
        showPage(dots, 0);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                showPage(dots, position);
            }
        });
    }

    /**
     * Moves the dots and the button's wording to a page.
     *
     * <p>The last page says something else, because "Next" on the last of four is a promise of a
     * fifth. It is the same button either way — what it does is decided when it is pressed.
     */
    private void showPage(ViewGroup dots, int selected) {
        for (int i = 0; i < dots.getChildCount(); i++) {
            dots.getChildAt(i).setBackgroundResource(i == selected
                    ? R.drawable.bg_how_dot_active : R.drawable.bg_how_dot_idle);
        }
        next.setText(selected == STEPS.length - 1 ? R.string.how_to_done : R.string.next);
    }

    private void advance() {
        int at = pager.getCurrentItem();
        if (at >= STEPS.length - 1) {
            finish();
            return;
        }
        pager.setCurrentItem(at + 1, true);
    }

    /** Dark to the edges, but nothing readable underneath a system bar. */
    private void applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.howRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }
}
