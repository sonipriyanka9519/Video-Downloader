package com.ms.webview.ui.tabs;

import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.ms.webview.R;
import com.ms.webview.ads.AdIds;
import com.ms.webview.ads.Banners;

import java.util.ArrayList;
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

        /** Opens a tab that writes nothing down. See Tab.incognito. */
        void newPrivateTab();

        /** Whether a private tab has been opened this session — decides if the segments show. */
        boolean hasPrivateTabs();
    }

    private TabAdapter adapter;

    /** The banner, held so it can be released with the view that showed it. */
    private ViewGroup tabsAdSlot;

    private TextView title;
    private View segments;
    private MaterialButton segmentTabs;
    private MaterialButton segmentPrivate;
    private View gridHolder;
    private View privateCaption;
    private MaterialButton newTab;
    /** Which segment is in front. Private only ever while there is a private segment to be on. */
    private boolean showingPrivate;

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

    /**
     * The banner goes with the view, not with the fragment.
     *
     * <p>This sheet is opened and dismissed constantly - it is how tabs are switched - and a banner
     * left attached to a destroyed view keeps a running request and a web view of its own alive,
     * once per open.
     */
    @Override
    public void onDestroyView() {
        Banners.destroy(tabsAdSlot);
        super.onDestroyView();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {

        title = view.findViewById(R.id.tabsTitle);

        tabsAdSlot = view.findViewById(R.id.tabsAdSlot);
        Banners.load(requireContext(), tabsAdSlot, AdIds.banner());
        RecyclerView grid = view.findViewById(R.id.tabGrid);

        adapter = new TabAdapter(this);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), COLUMNS));
        grid.setAdapter(adapter);

        segments = view.findViewById(R.id.tabSegments);
        gridHolder = view.findViewById(R.id.tabGridHolder);
        privateCaption = view.findViewById(R.id.privateCaption);
        newTab = view.findViewById(R.id.btnNewTab);

        view.findViewById(R.id.btnCloseSwitcher).setOnClickListener(v -> dismiss());

        newTab.setOnClickListener(v -> {
            // The button follows the segment: on the private side it opens a private tab, which
            // is what makes the mode reachable without a menu.
            if (showingPrivate) host().newPrivateTab();
            else host().newTab();
            dismiss();
        });

        segmentTabs = view.findViewById(R.id.segmentTabs);
        segmentPrivate = view.findViewById(R.id.segmentPrivate);
        segmentTabs.setOnClickListener(v -> selectSegment(false));
        segmentPrivate.setOnClickListener(v -> selectSegment(true));

        view.findViewById(R.id.btnTabsMenu).setOnClickListener(this::showMenu);

        refresh();
    }

    private void selectSegment(boolean privateSide) {
        if (showingPrivate == privateSide) return;
        showingPrivate = privateSide;
        refresh();
    }

    /**
     * Everything that changes between the two segments.
     *
     * <p>An ink wash behind the grid, an ink primary button instead of the accent one, and a
     * caption saying what private means. The wash is the point: it has to be unmistakable
     * mid-scroll, when the segmented control has gone off the top of the screen.
     */
    private void applySegmentStyle() {
        // Selected, not checked: the raised pill and the ink text both come from
        // state_selected on the segment's own background and text-colour selectors.
        segmentTabs.setSelected(!showingPrivate);
        segmentPrivate.setSelected(showingPrivate);

        int wash = ContextCompat.getColor(requireContext(),
                showingPrivate ? R.color.ds_private_wash : android.R.color.transparent);
        gridHolder.setBackgroundColor(wash);
        privateCaption.setVisibility(showingPrivate ? View.VISIBLE : View.GONE);

        newTab.setText(showingPrivate ? R.string.new_private_tab : R.string.new_tab);
        newTab.setIconResource(showingPrivate ? R.drawable.ic_eye_off : R.drawable.ic_add);

        int fill = ContextCompat.getColor(requireContext(),
                showingPrivate ? R.color.ds_ink : R.color.ds_accent);
        int on = ContextCompat.getColor(requireContext(),
                showingPrivate ? R.color.ds_bg : R.color.ds_on_accent);
        newTab.setBackgroundTintList(ColorStateList.valueOf(fill));
        newTab.setTextColor(on);
        newTab.setIconTint(ColorStateList.valueOf(on));
    }

    /**
     * The overflow: everything that is not opening a tab.
     *
     * <p>Closing every tab lives here rather than beside the count, where it sat next to the
     * button people press most and could not be taken back. A menu is one deliberate tap further
     * away, which is the right distance for it.
     */
    private void showMenu(View anchor) {
        androidx.appcompat.widget.PopupMenu menu = new PopupMenu(new ContextThemeWrapper(
                requireContext(), R.style.ThemeOverlay_Ds_PopupMenu), anchor);
        menu.getMenu().add(R.string.close_all_tabs).setOnMenuItemClickListener(item -> {
            confirmCloseAll();
            return true;
        });
        menu.show();
    }

    /**
     * Behind a confirm that names the real count.
     *
     * <p>The only action here that cannot be taken back. A single tab closes with an Undo in the
     * snackbar; closing every one of them at once is a different kind of decision and is asked
     * about rather than reported afterwards.
     */
    private void confirmCloseAll() {
        int count = host().tabs().size();
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.close_all_tabs)
                .setMessage(getResources().getQuantityString(
                        R.plurals.close_all_tabs_message, count, count))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.close_all, (d, which) -> {
                    host().closeAllTabs();
                    dismiss();
                })
                .show();
    }

    private void refresh() {
        List<Tab> all = host().tabs();

        // The segments appear only once a private tab exists this session, and vanish again with
        // the last one — so a viewer who has never used the mode never sees it advertised.
        boolean anyPrivate = host().hasPrivateTabs();
        segments.setVisibility(anyPrivate ? View.VISIBLE : View.GONE);
        if (!anyPrivate && showingPrivate) {
            // The last private tab has gone while the private segment was in front.
            showingPrivate = false;
        }

        List<Tab> shown = new ArrayList<>();
        for (Tab tab : all) {
            if (tab.incognito == showingPrivate) shown.add(tab);
        }

        adapter.submit(shown, host().currentTabId());
        title.setText(getString(R.string.tabs_with_count, shown.size()));
        applySegmentStyle();
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
        // On the private side, closing the last one is the mode ending — the segment goes with
        // it and refresh has already dropped back to Tabs, so there is something to look at.
        if (showingPrivate) return;
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
