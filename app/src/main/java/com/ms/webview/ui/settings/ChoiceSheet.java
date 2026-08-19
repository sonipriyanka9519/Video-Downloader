package com.ms.webview.ui.settings;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.ms.webview.R;

import java.util.List;

/**
 * A choice with a handful of answers — screen 10, panel C.
 *
 * <p>A sheet rather than a screen, and it applies on tap rather than on an OK. The design is
 * explicit about this and it is right: these are single decisions with four or five answers, and
 * a page plus a confirm would be two more steps than any of them is worth.
 *
 * <p>Generic over the label so the same sheet serves default quality, the theme and the parallel
 * count — three lists that differ only in what they are lists of.
 */
public final class ChoiceSheet {

    public interface Listener<T> {
        void onChosen(T choice);
    }

    private ChoiceSheet() {
    }

    /**
     * @param body what the choice governs; null when the title says it all
     * @param labels one per option, in the order they should be read
     */
    public static <T> void show(@NonNull Context context, @NonNull CharSequence title,
                                @Nullable CharSequence body, @NonNull List<T> options,
                                @NonNull List<CharSequence> labels, @Nullable T current,
                                @NonNull Listener<T> listener) {
        show(context, title, body, options, labels, null, current, listener);
    }

    /**
     * The same sheet, with a line under each option saying what choosing it costs.
     *
     * <p>Screen 17's default-quality sheet is the reason this exists: "Best available" and
     * "Smallest" only mean anything next to each other, and nobody should have to learn which one
     * fills their storage by filling it. Optional, because the theme sheet has nothing to add and
     * a subtitle slot left empty would make three one-line rows into three two-line ones.
     *
     * @param bodies one per option, in the same order as {@code labels}; null for none
     */
    public static <T> void show(@NonNull Context context, @NonNull CharSequence title,
                                @Nullable CharSequence body, @NonNull List<T> options,
                                @NonNull List<CharSequence> labels,
                                @Nullable List<CharSequence> bodies, @Nullable T current,
                                @NonNull Listener<T> listener) {
        View content = LayoutInflater.from(context)
                .inflate(R.layout.sheet_settings_choice, null, false);

        ((TextView) content.findViewById(R.id.choiceTitle)).setText(title);
        TextView bodyView = content.findViewById(R.id.choiceBody);
        bodyView.setVisibility(TextUtils.isEmpty(body) ? View.GONE : View.VISIBLE);
        bodyView.setText(body);

        BottomSheetDialog dialog = new BottomSheetDialog(context,
                R.style.ThemeOverlay_Ds_BottomSheet);
        dialog.setContentView(content);

        LinearLayout list = content.findViewById(R.id.choiceList);
        LayoutInflater inflater = LayoutInflater.from(context);
        for (int i = 0; i < options.size(); i++) {
            T option = options.get(i);
            View row = inflater.inflate(R.layout.item_settings_choice, list, false);

            ((TextView) row.findViewById(R.id.choiceLabel)).setText(
                    i < labels.size() ? labels.get(i) : String.valueOf(option));

            CharSequence detail = bodies != null && i < bodies.size() ? bodies.get(i) : null;
            TextView optionBody = row.findViewById(R.id.choiceOptionBody);
            optionBody.setVisibility(TextUtils.isEmpty(detail) ? View.GONE : View.VISIBLE);
            optionBody.setText(detail);
            ((RadioButton) row.findViewById(R.id.choiceRadio)).setChecked(option.equals(current));

            row.setOnClickListener(v -> {
                // Closed first, then reported. The caller usually redraws the row behind this
                // sheet, and doing that under a sheet still on screen shows the change happening
                // through a gap rather than after it has gone.
                dialog.dismiss();
                listener.onChosen(option);
            });
            list.addView(row);
        }
        dialog.show();
    }

    /** Convenience for the common case: no explanatory line under the title. */
    public static <T> void show(@NonNull Context context, @NonNull CharSequence title,
                                @NonNull List<T> options, @NonNull List<CharSequence> labels,
                                @Nullable T current, @NonNull Listener<T> listener) {
        show(context, title, null, options, labels, current, listener);
    }
}
