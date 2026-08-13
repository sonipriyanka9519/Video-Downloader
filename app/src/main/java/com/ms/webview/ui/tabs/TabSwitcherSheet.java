package com.ms.webview.ui.tabs;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.ms.webview.R;

import java.util.List;

/**
 * The open tabs, as cards.
 *
 * <p>Holds no tabs of its own. The browser owns the list and every action here is handed back to
 * it through {@link Host}, so there is one copy of the truth and the sheet cannot drift from it.
 */
public class TabSwitcherSheet extends BottomSheetDialogFragment
        implements TabAdapter.Listener {

    private static final int COLUMNS = 2;

    /** Implemented by the browser, which owns the tabs. */
    public interface Host {
        List<Tab> tabs();

        @Nullable
        String currentTabId();

        void openTab(Tab tab);

        void closeTab(Tab tab);

        void newTab();

        void closeAllTabs();
    }

    private TabAdapter adapter;
    private TextView title;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_tabs, container, false);
    }

    /**
     * Opened at full height rather than as a peek.
     *
     * <p>This is a screen, not a prompt: a grid of cards that the viewer scans and picks from. A
     * sheet that opened half-way would show one row and ask for a drag before it could be used.
     */
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
        title = view.findViewById(R.id.tabsTitle);
        RecyclerView grid = view.findViewById(R.id.tabGrid);

        adapter = new TabAdapter(this);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), COLUMNS));
        grid.setAdapter(adapter);

        view.findViewById(R.id.btnCloseSwitcher).setOnClickListener(v -> dismiss());

        view.findViewById(R.id.btnNewTab).setOnClickListener(v -> {
            host().newTab();
            dismiss();
        });

        view.findViewById(R.id.btnTabsMenu).setOnClickListener(this::showMenu);

        refresh();
    }

    /**
     * The overflow: everything that is not opening a tab.
     *
     * <p>Closing every tab lives here rather than beside the count, where it sat next to the
     * button people press most and could not be taken back. A menu is one deliberate tap further
     * away, which is the right distance for it.
     */
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(R.string.close_all_tabs).setOnMenuItemClickListener(item -> {
            host().closeAllTabs();
            dismiss();
            return true;
        });
        menu.show();
    }

    private void refresh() {
        List<Tab> tabs = host().tabs();
        adapter.submit(tabs, host().currentTabId());
        title.setText(getString(R.string.tabs) + "  " + tabs.size());
    }

    @Override
    public void onOpenTab(Tab tab) {
        host().openTab(tab);
        dismiss();
    }

    /**
     * Closing does not dismiss: shutting several tabs in a row is the normal way to use this, and
     * a sheet that vanished after each one would make that four gestures instead of one.
     */
    @Override
    public void onCloseTab(Tab tab) {
        host().closeTab(tab);
        refresh();
        // The browser always keeps one tab, so an emptied list means a fresh one is in front and
        // there is nothing left here to look at.
        if (host().tabs().size() <= 1) dismiss();
    }

    /**
     * The browser shows this through its own child manager, so it is the parent fragment. The
     * activity is checked too, which costs nothing and keeps the sheet usable if that changes.
     */
    private Host host() {
        if (getParentFragment() instanceof Host) return (Host) getParentFragment();
        if (getActivity() instanceof Host) return (Host) getActivity();
        throw new IllegalStateException("TabSwitcherSheet needs a Host");
    }
}
