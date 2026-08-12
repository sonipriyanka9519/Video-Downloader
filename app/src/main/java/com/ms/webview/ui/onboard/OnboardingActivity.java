package com.ms.webview.ui.onboard;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.viewpager2.widget.ViewPager2;

import com.ms.webview.MainActivity;
import com.ms.webview.R;

/** Shown once, on first launch. */
public class OnboardingActivity extends AppCompatActivity {

    private ViewPager2 pager;
    private LinearLayout dots;
    private TextView next;
    private TextView skip;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_onboarding);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.onboardRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        pager = findViewById(R.id.onboardPager);
        dots = findViewById(R.id.onboardDots);
        next = findViewById(R.id.btnNext);
        skip = findViewById(R.id.btnSkip);

        pager.setAdapter(new OnboardPagerAdapter());
        buildDots();

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                paintDots(position);
                boolean last = position == OnboardPagerAdapter.pageCount() - 1;
                next.setText(last ? R.string.get_started : R.string.next);
                // Skipping to the end of something you are already at the end of is not an
                // offer worth making.
                skip.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
            }
        });
        paintDots(0);

        next.setOnClickListener(v -> {
            int at = pager.getCurrentItem();
            if (at < OnboardPagerAdapter.pageCount() - 1) {
                pager.setCurrentItem(at + 1, true);
            } else {
                finishOnboarding();
            }
        });
        skip.setOnClickListener(v -> finishOnboarding());
    }

    /**
     * The dot for the current page is a short bar rather than a larger circle: it reads as
     * progress along a line, which is what the pager actually is.
     */
    private void buildDots() {
        int size = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._6sdp);
        int gap = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._4sdp);

        for (int i = 0; i < OnboardPagerAdapter.pageCount(); i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginEnd(gap);
            dot.setLayoutParams(lp);
            dots.addView(dot);
        }
    }

    private void paintDots(int selected) {
        int size = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._6sdp);
        int wide = getResources().getDimensionPixelSize(com.intuit.sdp.R.dimen._18sdp);

        for (int i = 0; i < dots.getChildCount(); i++) {
            View dot = dots.getChildAt(i);
            boolean active = i == selected;
            dot.setBackgroundResource(
                    active ? R.drawable.bg_dot_active : R.drawable.bg_dot_inactive);

            ViewGroup.LayoutParams lp = dot.getLayoutParams();
            lp.width = active ? wide : size;
            dot.setLayoutParams(lp);
        }
    }

    private void finishOnboarding() {
        Onboarding.markDone(this);
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
