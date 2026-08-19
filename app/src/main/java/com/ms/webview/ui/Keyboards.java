package com.ms.webview.ui;

import android.content.Context;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * The one rule this app has about the soft keyboard.
 *
 * <p>A list under an open keyboard is showing half of itself. The moment somebody drags it they
 * have stopped typing and started reading, and the keyboard is in the way of the thing they are
 * now looking at — so it goes, without their having to find a back gesture to dismiss it.
 *
 * <p>On the drag, not on the settle. Waiting for the scroll to stop would take the keyboard away
 * after the reading had already been done through a half-height window.
 */
public final class Keyboards {

    private Keyboards() {
    }

    /** Puts the keyboard away as soon as this list is dragged. */
    public static void hideOnScroll(@NonNull RecyclerView list) {
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state != RecyclerView.SCROLL_STATE_DRAGGING) return;
                hide(view);
            }
        });
    }

    /**
     * Hides the keyboard, and drops the caret with it.
     *
     * <p>Clearing focus matters as much as hiding: a field that keeps it brings the keyboard
     * straight back the next time the window is touched, which reads as the dismissal not having
     * worked.
     */
    public static void hide(@NonNull View anyViewInWindow) {
        Context context = anyViewInWindow.getContext();
        InputMethodManager ime = (InputMethodManager)
                context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (ime == null) return;

        View focused = anyViewInWindow.getRootView().findFocus();
        View token = focused != null ? focused : anyViewInWindow;
        ime.hideSoftInputFromWindow(token.getWindowToken(), 0);
        if (focused != null) focused.clearFocus();
    }
}
