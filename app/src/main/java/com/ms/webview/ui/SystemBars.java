package com.ms.webview.ui;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Keeps a screen's content clear of the status bar and the gesture area.
 *
 * <p>Needed on every screen now, not only the ones that ask for it: from targetSdk 35 Android
 * draws every app edge to edge whether it opted in or not, so a layout that used to be inset by
 * the system is suddenly underneath both bars.
 *
 * <p>A helper rather than the same five lines in each activity, because the part that is easy to
 * get wrong is not the listener — it is remembering to add the inset to the padding the layout
 * was inflated with rather than to whatever padding the view currently has. Insets arrive again
 * on every rotation, and adding to the current value compounds it each time.
 */
public final class SystemBars {

    private SystemBars() {
    }

    /** Pads a root view by all four system-bar insets, once per delivery and never cumulatively. */
    public static void pad(@NonNull View root) {
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(left + bars.left, top + bars.top,
                    right + bars.right, bottom + bars.bottom);
            return insets;
        });
        // The window's first inset pass has usually happened by the time a view exists, and a
        // listener attached afterwards is never called on its own.
        ViewCompat.requestApplyInsets(root);
    }

    /**
     * Pads a sheet clear of the gesture area, and nothing else.
     *
     * <p>A bottom sheet needs only this one: it is anchored to the bottom of the screen and never
     * reaches the top, so its own contents are the only thing that can end up underneath the
     * navigation bar. Material insets the sheet's container but not the layout inside it, which is
     * how a primary button ends up sharing its place with the gesture pill.
     */
    public static void padBottom(@NonNull View root) {
        final int bottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(view.getPaddingLeft(), view.getPaddingTop(),
                    view.getPaddingRight(), bottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
