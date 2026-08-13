package com.ms.webview.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.ms.webview.R;
import com.ms.webview.ui.home.SearchHistory;

import java.util.List;

/**
 * Every page the browser has been to.
 *
 * <p>A screen of its own, because it is long. It used to be offered under the address bar, where
 * it buried the one or two suggestions that were actually about what the viewer had begun to
 * type.
 */
public class HistorySheet extends BottomSheetDialogFragment
        implements SearchHistoryAdapter.Listener {

    /** Implemented by the browser, which is what actually opens a page. */
    public interface Host {
        void onOpenHistory(SearchHistory.Entry entry);
    }

    private SearchHistoryAdapter adapter;
    private TextView empty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_history, container, false);
    }

    /** Full height: a list this long is a screen, not a prompt. */
    @Override
    public void onStart() {
        super.onStart();
        View sheet = requireDialog().findViewById(
                com.google.android.material.R.id.design_bottom_sheet);
        if (sheet == null) return;

        sheet.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
        sheet.requestLayout();

        BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        behavior.setSkipCollapsed(true);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        empty = view.findViewById(R.id.historyEmpty);
        RecyclerView list = view.findViewById(R.id.historyList);

        adapter = new SearchHistoryAdapter(requireContext(), this);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        view.findViewById(R.id.btnCloseHistory).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.btnClearHistory).setOnClickListener(v -> {
            SearchHistory.clear(requireContext());
            refresh();
        });

        refresh();
    }

    private void refresh() {
        List<SearchHistory.Entry> entries = SearchHistory.all(requireContext());
        adapter.submit(entries);
        empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onOpenHistory(SearchHistory.Entry entry) {
        host().onOpenHistory(entry);
        dismiss();
    }

    /** Removing one does not dismiss: tidying a list is done several rows at a time. */
    @Override
    public void onRemoveHistory(SearchHistory.Entry entry) {
        SearchHistory.remove(requireContext(), entry.url);
        refresh();
    }

    private Host host() {
        if (getParentFragment() instanceof Host) return (Host) getParentFragment();
        if (getActivity() instanceof Host) return (Host) getActivity();
        throw new IllegalStateException("HistorySheet needs a Host");
    }
}
