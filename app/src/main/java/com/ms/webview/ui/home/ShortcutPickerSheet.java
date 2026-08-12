package com.ms.webview.ui.home;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.ms.webview.R;

import java.util.List;

/**
 * The Add sheet: everything the app supports that is not already on the grid.
 *
 * <p>A picker rather than an address field, because the tiles are tied to what the app can
 * actually pull a video from — there is nothing useful the user could type here.
 */
public class ShortcutPickerSheet extends BottomSheetDialogFragment
        implements ShortcutAdapter.Listener {

    private static final int GRID_COLUMNS = 4;

    /** Told when the grid behind the sheet needs redrawing. */
    public interface Host {
        void onShortcutsChanged();
    }

    private ShortcutAdapter adapter;
    private RecyclerView grid;
    private View empty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_shortcut_picker, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        grid = view.findViewById(R.id.pickerGrid);
        empty = view.findViewById(R.id.pickerEmpty);

        adapter = new ShortcutAdapter(this, false);
        grid.setLayoutManager(new GridLayoutManager(requireContext(), GRID_COLUMNS));
        grid.setAdapter(adapter);
        refresh();
    }

    private void refresh() {
        List<Shortcut> available = Shortcuts.hidden(requireContext());
        adapter.submit(available);

        boolean none = available.isEmpty();
        grid.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
    }

    /** A tap in this grid adds rather than opens. */
    @Override
    public void onOpen(Shortcut shortcut) {
        Shortcuts.add(requireContext(), shortcut);
        notifyHost();
        // Closing on each pick would make adding three sites three trips, so the sheet stays
        // and simply loses the tile that was taken.
        refresh();
    }

    @Override
    public void onAdd() {
        // No add tile in this grid.
    }

    @Override
    public void onRemove(Shortcut shortcut) {
        // Nothing to remove from a list of things that are not on the grid.
    }

    private void notifyHost() {
        Host host = null;
        if (getParentFragment() instanceof Host) host = (Host) getParentFragment();
        else if (getActivity() instanceof Host) host = (Host) getActivity();
        if (host != null) host.onShortcutsChanged();
    }
}
