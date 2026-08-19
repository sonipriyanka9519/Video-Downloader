package com.ms.webview.ui;

import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.ui.history.HistoryAdapter;
import com.ms.webview.ui.home.SearchHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * History — screen 12.
 *
 * <p>A lookup rather than a destination: 90% of the screen, the page still visible behind it, and a
 * tap on a row returns to that page in the tab already open. Everything it can do — search, forget
 * one, forget all — happens inside it, so nothing here navigates anywhere.
 *
 * <p>Private browsing writes nothing to the store this reads, so there is no private filter here and
 * nothing to explain about one. See BrowserFragment.noteTabPage.
 */
public class HistorySheet extends BottomSheetDialogFragment
        implements HistoryAdapter.Listener {

    /** How much of the screen the sheet takes — the design's 90%. */
    private static final float HEIGHT_FRACTION = 0.9f;

    /** Implemented by the browser, which is what actually opens a page. */
    public interface Host {
        void onOpenHistory(SearchHistory.Entry entry);
    }

    private HistoryAdapter adapter;
    private View empty;
    private TextView emptyTitle;
    private TextView emptyBody;
    private TextView clearAll;
    private EditText search;
    private View clearSearch;

    /** Every visit, read once per refresh; the query filters this rather than the store. */
    private final List<SearchHistory.Entry> entries = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_history, container, false);
    }

    /**
     * Nine tenths of the screen, expanded, and not collapsible.
     *
     * <p>The remaining tenth is the point rather than a margin: the page underneath stays visible,
     * which is what makes this read as something laid over the browser instead of a screen the app
     * moved to. Skipping the collapsed state means the swipe that would half-close it dismisses it,
     * because a half-open list of history is no use to anybody.
     */
    @Override
    public void onStart() {
        super.onStart();
        View sheet = requireDialog().findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;

        DisplayMetrics metrics = Resources.getSystem().getDisplayMetrics();
        sheet.getLayoutParams().height = Math.round(metrics.heightPixels * HEIGHT_FRACTION);
        sheet.requestLayout();

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        empty = view.findViewById(R.id.historyEmpty);
        emptyTitle = view.findViewById(R.id.historyEmptyTitle);
        emptyBody = view.findViewById(R.id.historyEmptyBody);
        clearAll = view.findViewById(R.id.btnClearHistory);
        search = view.findViewById(R.id.historySearch);
        clearSearch = view.findViewById(R.id.btnClearSearch);
        RecyclerView list = view.findViewById(R.id.historyList);

        adapter = new HistoryAdapter(requireContext(), this);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);
        // Scrolling a list is reading it, not typing into it — see Keyboards.hideOnScroll.
        Keyboards.hideOnScroll(list);

        clearAll.setOnClickListener(v -> confirmClearAll());
        clearSearch.setOnClickListener(v -> search.setText(""));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clearSearch.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                // Filtered from what is already in memory rather than re-read from the store: this
                // runs on every keystroke, and the list cannot change while the sheet is up.
                showList();
            }
        });

        // The list is already filtered by the time the search key is reachable, so it only puts the
        // keyboard away. Handled rather than ignored: a key that visibly does nothing reads as the
        // search having failed.
        search.setOnEditorActionListener((v, actionId, event) -> {
            InputMethodManager ime = (InputMethodManager)
                    requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (ime != null) ime.hideSoftInputFromWindow(search.getWindowToken(), 0);
            search.clearFocus();
            return true;
        });

        // Anything that has aged out goes now, before the list is drawn — otherwise the first thing
        // somebody who asked for 30-day retention sees is the pages it was supposed to have removed.
        SearchHistory.pruneOld(requireContext());
        refresh();
    }

    /** Re-reads the store. Called when the sheet opens and after anything is forgotten. */
    private void refresh() {
        entries.clear();
        entries.addAll(SearchHistory.all(requireContext()));
        showList();
    }

    private void showList() {
        String query = search.getText() == null ? "" : search.getText().toString().trim();
        List<SearchHistory.Entry> shown = query.isEmpty() ? entries : matching(query);

        adapter.submit(shown, query);
        bindEmpty(shown.isEmpty(), !query.isEmpty());

        // Nothing to clear, so the control says so by going quiet rather than by disappearing —
        // a header whose contents move as the list empties is a header that has to be re-read.
        boolean anything = !entries.isEmpty();
        clearAll.setEnabled(anything);
        clearAll.setTextColor(ContextCompat.getColor(requireContext(),
                anything ? R.color.ds_accent : R.color.ds_ink_faint));
    }

    /**
     * Visits whose title or host contains the query.
     *
     * <p>Both, because people remember pages either way — by what the page was called or by which
     * site it was on — and a search that only looked at one of them would fail silently for anybody
     * who thought in the other.
     */
    private List<SearchHistory.Entry> matching(String query) {
        String needle = query.toLowerCase(Locale.getDefault());
        List<SearchHistory.Entry> out = new ArrayList<>();
        for (SearchHistory.Entry entry : entries) {
            String title = entry.label().toLowerCase(Locale.getDefault());
            String host = Formats.hostOf(entry.url).toLowerCase(Locale.getDefault());
            if (title.contains(needle) || host.contains(needle)) out.add(entry);
        }
        return out;
    }

    /**
     * The two empty states, which are different facts.
     *
     * <p>"No history yet" over a search that found nothing would read as the search having deleted
     * it. The search bar stays put in that case — it is what the viewer would want to change.
     */
    private void bindEmpty(boolean nothingShown, boolean searching) {
        empty.setVisibility(nothingShown ? View.VISIBLE : View.GONE);
        if (!nothingShown) return;

        emptyTitle.setText(searching
                ? R.string.history_no_matches : R.string.history_empty_title);
        emptyBody.setText(searching
                ? R.string.history_no_matches_body : R.string.history_empty_body);
    }

    // ------------------------------------------------------------------ rows

    @Override
    public void onOpenVisit(SearchHistory.Entry entry) {
        host().onOpenHistory(entry);
        dismiss();
    }

    /** Forgetting one does not dismiss: tidying a list is done several rows at a time. */
    @Override
    public void onRemoveVisit(SearchHistory.Entry entry) {
        SearchHistory.remove(requireContext(), entry.url);
        refresh();
    }

    // ------------------------------------------------------------------ clear all

    /**
     * The only bulk action, behind a confirm that names the real count.
     *
     * <p>The first time it also puts the retention question, pre-ticked — screen 12, panel D. Once
     * answered it is never asked again and the answer lives in Settings, so this is the one moment
     * the offer exists: somebody clearing their history is, by definition, thinking about how much
     * of it they want kept.
     */
    private void confirmClearAll() {
        int count = entries.size();
        if (count == 0) return;

        boolean ask = !SearchHistory.retentionAsked(requireContext());

        View body = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_clear_history, null, false);
        ((TextView) body.findViewById(R.id.clearMessage)).setText(
                getResources().getQuantityString(R.plurals.clear_history_body, count, count));

        View block = body.findViewById(R.id.retentionBlock);
        MaterialCheckBox check = body.findViewById(R.id.retentionCheck);
        block.setVisibility(ask ? View.VISIBLE : View.GONE);
        // The row toggles, not the box: one target the width of the sentence rather than an 18dp
        // square beside it.
        block.setOnClickListener(v -> check.setChecked(!check.isChecked()));

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.clear_history_all_title)
                .setView(body)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.clear, (dialog, which) -> {
                    if (ask) {
                        SearchHistory.markRetentionAsked(requireContext());
                        SearchHistory.setAutoClearOld(requireContext(), check.isChecked());
                    }
                    SearchHistory.clear(requireContext());
                    // The field would otherwise be filtering an empty list and reporting 0 results.
                    search.setText("");
                    refresh();
                })
                .show();
    }

    private Host host() {
        if (getParentFragment() instanceof Host) return (Host) getParentFragment();
        if (getActivity() instanceof Host) return (Host) getActivity();
        throw new IllegalStateException("HistorySheet needs a Host");
    }
}
