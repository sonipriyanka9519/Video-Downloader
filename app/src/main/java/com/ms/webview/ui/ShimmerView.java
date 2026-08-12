package com.ms.webview.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

/**
 * A loading placeholder: a highlight sweeping across a flat block.
 *
 * <p>Hand-rolled rather than pulled in as a dependency — it is one gradient and one animator,
 * and it takes its colours from the theme so it stays right if the palette changes.
 *
 * <p>The animation is tied to attachment and visibility, so an off-screen page in the pager is
 * never quietly burning frames.
 */
public class ShimmerView extends View {

    private static final long SWEEP_MS = 1150;
    /** How much of the width the bright band spans. */
    private static final float BAND = 0.55f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();

    @Nullable
    private LinearGradient gradient;
    @Nullable
    private ValueAnimator animator;
    private float offset;

    public ShimmerView(Context context) {
        this(context, null);
    }

    public ShimmerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width <= 0 || height <= 0) return;

        int base = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceVariant);
        // A touch of the surface colour mixed in reads as a highlight on both light and dark
        // base colours, where a hardcoded white would only work on one.
        int surface = MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurface);
        int highlight = ColorUtils.blendARGB(base, surface, 0.7f);

        gradient = new LinearGradient(0, 0, width * BAND, 0,
                new int[]{base, highlight, base},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        paint.setShader(gradient);
        restart();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (gradient == null) return;
        matrix.setTranslate(offset, 0);
        gradient.setLocalMatrix(matrix);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) restart();
        else stop();
    }

    private void restart() {
        stop();
        if (getWidth() <= 0 || getVisibility() != VISIBLE || !isAttachedToWindow()) return;

        float travel = getWidth() * (1f + BAND);
        animator = ValueAnimator.ofFloat(-getWidth() * BAND, travel);
        animator.setDuration(SWEEP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    private void stop() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }
}
