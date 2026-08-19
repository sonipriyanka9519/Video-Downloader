package com.ms.webview.ui.downloads;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.List;

/**
 * A chip row that centres each line of chips rather than leaving the last one short.
 *
 * <p>ChipGroup wraps, which is what the filter row wants, but it packs every line to the start —
 * so six chips over two lines leave the second line with one chip hanging off the left edge and a
 * gap of empty row beside it. Read as a shape, that looks like a mistake rather than a row that
 * happened to wrap.
 *
 * <p>Done by shifting after the fact rather than by laying out from scratch: the group's own
 * measuring and wrapping are exactly right, and the only thing wrong with the result is where each
 * line sits. Every child keeps the width it was given; the whole line simply moves.
 */
public class CenteredChipGroup extends ChipGroup {

    public CenteredChipGroup(@NonNull Context context) {
        super(context);
    }

    public CenteredChipGroup(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CenteredChipGroup(@NonNull Context context, @Nullable AttributeSet attrs, int style) {
        super(context, attrs, style);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        int usable = getWidth() - getPaddingLeft() - getPaddingRight();
        if (usable <= 0) return;

        // Grouped by the top edge, because that is what a line is here: children the group placed
        // at the same height. Nothing else in the layout knows how many lines there turned out to
        // be, and asking the group would mean repeating its wrapping logic.
        List<View> line = new ArrayList<>();
        int lineTop = Integer.MIN_VALUE;

        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;

            if (child.getTop() != lineTop) {
                centre(line, usable);
                line.clear();
                lineTop = child.getTop();
            }
            line.add(child);
        }
        centre(line, usable);
    }

    /** Slides one line sideways so the space left over is shared either side of it. */
    private void centre(List<View> line, int usable) {
        if (line.isEmpty()) return;

        int start = line.get(0).getLeft();
        int end = line.get(line.size() - 1).getRight();
        int slack = usable - (end - start);
        // A full line has nothing to share and must not move: shifting it by a rounded-down half
        // would knock it off the padding it was aligned to.
        if (slack <= 0) return;

        int shift = slack / 2;
        for (View child : line) {
            child.offsetLeftAndRight(shift);
        }
    }
}
