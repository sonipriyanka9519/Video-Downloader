package com.ms.webview.ui;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.ms.webview.App;
import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.data.MediaLibrary;
import com.ms.webview.download.DownloadNotifier;
import com.ms.webview.download.DownloadService;
import com.ms.webview.MainActivity;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.ui.downloads.CollectionSheet;
import com.ms.webview.ui.downloads.CollectionStore;
import com.ms.webview.ui.downloads.DownloadAdapter;
import com.ms.webview.ui.downloads.DownloadPrefs;
import com.ms.webview.ui.player.PlayerQueue;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.ms.webview.ui.downloads.DownloadFilter;
import com.ms.webview.ui.downloads.DownloadSort;
import com.ms.webview.ui.downloads.WatchedStore;
import com.ms.webview.ui.growth.RatePrompt;
import com.ms.webview.ui.notify.UnwatchedReminder;
import com.ms.webview.ui.downloads.PrivateStore;
import com.ms.webview.ui.guide.WalkthroughActivity;
import com.ms.webview.ui.lock.PrivateAuth;
import com.ms.webview.ui.lock.PrivateFolderActivity;
import com.ms.webview.ui.settings.SettingsPrefs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything downloaded, in one list.
 *
 * <p>It was two tabs — in progress and finished — and the split cost more than it explained. A
 * download that completed while you were watching it left the page you were on for the one you were
 * not, and the question this screen exists to answer, "did the thing I just saved arrive", needed
 * both tabs to answer it. One list answers it by scrolling: whatever is still arriving is pinned to
 * the top, and everything else sits under the day it belongs to.
 */
public class DownloadsFragment extends Fragment implements DownloadAdapter.Actions {

    /** Two columns in the grid: at three, a title stops fitting on one line and starts lying. */
    private static final int GRID_COLUMNS = 2;

    private DownloadAdapter adapter;
    private RecyclerView list;
    private View empty;
    private ImageView emptyIcon;
    private TextView emptyTitle;
    private TextView emptyBody;
    private View howToButton;
    private ImageButton layoutToggle;

    /**
     * The three states of the bar at the top. Exactly one of them is visible.
     *
     * <p>They replace each other rather than stacking, because each is the screen's title while
     * it is up — two headings would leave it unclear which one the list below answers to.
     */
    private View header;
    private View searchBar;
    private EditText searchInput;
    private View selectBar;
    private TextView selectCount;
    /** Select mode's actions, which take the tab bar's place at the foot of the window. */
    private View selectActions;
    private View filterRow;
    /** Screen 13's explanation for a selection this screen did not make. */
    private View watchedBanner;
    private TextView watchedBannerText;

    /**
     * Set by the storage screen on its way here — screen 13, panel B.
     *
     * <p>A flag rather than a list of files, and static because this fragment may not exist yet when
     * the request is made. The set is worked out here, against the rows actually on screen, at the
     * moment the selection is made: a list of ids captured on another screen a moment earlier could
     * name a video that has since been deleted.
     */
    private static final AtomicBoolean REVIEW_WATCHED = new AtomicBoolean(false);
    /** Live only while rows are ticked, so back means what it usually means the rest of the time. */
    @Nullable
    private OnBackPressedCallback selectBack;

    /** What the chip row and the search field have narrowed the library to. */
    private DownloadFilter filter = DownloadFilter.ALL;
    private String query = "";
    /**
     * The collection the list is scoped to, or null for the whole library.
     *
     * <p>A separate axis from {@link #filter} on purpose — Unwatched inside Workout is a
     * reasonable thing to want, and folding collections into the same enum would have made
     * choosing one mean giving up the other.
     */
    @Nullable
    private String collection;

    /** The open collection's header — screen 08, panel D. Replaces the library's while it is up. */
    private View collectionBar;
    private TextView collectionTitle;
    private TextView collectionSubtitle;
    /** Screen 11's way in. Hidden until the lock exists. */
    private Chip privateChip;
    private ChipGroup filterChips;
    /** True while the code is moving a tick, so the listener does not mistake it for a tap. */
    private boolean syncingChips;
    @Nullable
    private CollectionSheet collectionSheet;

    private DownloadSort sort = DownloadSort.NEWEST;
    /** Kept so a change of order can redraw without waiting for the repository to speak again. */
    private List<DownloadEntity> all = new ArrayList<>();
    /** The row's own menu while it is up, so it can be closed with the screen. */
    @Nullable
    private BottomSheetDialog moreSheet;

    /** The private folder's file copies. One at a time, and never on the main thread. */
    private final ExecutorService privateIo = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<IntentSenderRequest> writeConfirmation;
    /**
     * What to do once the viewer has granted access, where anything is left to do.
     *
     * <p>A write request only grants permission — the change has to be made again afterwards. A
     * delete request from Android 11 carries the delete out itself, so that one leaves this null.
     */
    @Nullable
    private Runnable afterConfirmation;

    /**
     * Runs when a consent request comes back, granted or not. {@link #afterConfirmation} is the
     * retry and only runs on a yes; this is the reckoning, and a no is one of its answers.
     */
    @Nullable
    private Runnable onConfirmationReturn;

    /** The private folder's gatekeeper — registered in onCreate, used from every private path. */
    private PrivateAuth privateAuth;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        writeConfirmation = registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    Runnable retry = afterConfirmation;
                    afterConfirmation = null;
                    if (result.getResultCode() == android.app.Activity.RESULT_OK && retry != null) {
                        retry.run();
                    }
                    // Whatever the answer. A private move has to know that the request was refused
                    // as much as that it was granted — see moveSelectionToPrivate.
                    Runnable settle = onConfirmationReturn;
                    onConfirmationReturn = null;
                    if (settle != null) settle.run();
                    App.get().repository().refreshLibrary();
                });
        privateAuth = new PrivateAuth(this, requireContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_downloads, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        list = view.findViewById(R.id.downloadList);
        empty = view.findViewById(R.id.downloadEmpty);
        emptyIcon = view.findViewById(R.id.emptyIcon);
        emptyTitle = view.findViewById(R.id.emptyTitle);
        emptyBody = view.findViewById(R.id.emptyBody);
        howToButton = view.findViewById(R.id.btnEmptyHowTo);
        header = view.findViewById(R.id.downloadsHeader);
        layoutToggle = view.findViewById(R.id.btnDownloadLayout);

        adapter = new DownloadAdapter(this);
        list.setAdapter(adapter);
        // Dragging the list is reading it, not typing into it — see Keyboards.hideOnScroll.
        Keyboards.hideOnScroll(list);

        sort = DownloadPrefs.sort(requireContext());
        applyMode(DownloadPrefs.mode(requireContext()));

        layoutToggle.setOnClickListener(v -> applyMode(
                adapter.mode() == DownloadAdapter.Mode.LIST
                        ? DownloadAdapter.Mode.GRID : DownloadAdapter.Mode.LIST));
        view.findViewById(R.id.btnDownloadSort).setOnClickListener(v -> chooseSort());

        // The walkthrough's only entry on this screen now. It used to be a permanent row above
        // the nav bar, which covered the last item in the list for good; screen 06 moves it into
        // the empty state, where somebody with no downloads is the person who needs it.
        view.findViewById(R.id.btnEmptyHowTo).setOnClickListener(v ->
                WalkthroughActivity.open(requireContext()));

        setUpSearch(view);
        setUpFilters(view);
        setUpSelectMode(view);

        App.get().repository().observeAll().observe(getViewLifecycleOwner(), downloads -> {
            all = downloads == null ? new ArrayList<>() : downloads;
            // The library is the only thing that knows what is really on the device, so this is
            // where a collection finds out one of its videos has gone — whether this app deleted
            // it, the system did after asking, or the gallery did while we were closed.
            CollectionStore.retainAll(requireContext(), liveUris());
            showList();
        });
    }

    /**
     * The watch revision this list was last built against — see {@link WatchedStore#revision}.
     *
     * <p>Starts at -1 so the first build always happens.
     */
    private int shownWatchRevision = -1;

    /** Fewer than this is not a backlog. Matches UnwatchedReminder, deliberately. */
    private static final int REMINDER_THRESHOLD = 3;

    @Override
    public void onResume() {
        super.onResume();

        // Nothing happens on an ordinary tab switch, and that is the point.
        //
        // This used to rescan the library and rebuild the list every single time the tab came
        // forward. Both are expensive and neither was usually needed: the repository keeps a
        // ContentObserver on the video collection, so a video deleted from the gallery or saved
        // by another app already arrives here on its own. What the rescan bought was a case the
        // observer cannot see - being granted storage permission while this screen existed - and
        // that only matters until something has actually loaded.
        //
        // The rebuild cost more than it looked. adapter.submit ends in notifyDataSetChanged, so
        // every visible row rebound twice per visit: once from here and once when the rescan came
        // back. A rebound row redraws its watched bar from nothing, which is what made every
        // indicator sweep up from zero each time this tab was opened.
        if (all.isEmpty()) App.get().repository().refreshLibrary();

        // The one thing that genuinely changes while this screen is away is how far through a
        // video somebody got, because the player writes that as it closes and this is the screen
        // it closes back onto. So the list asks whether that happened rather than assuming it
        // did - and when it did, rebuilds, so the bar and the dot are right straight away and a
        // video just finished leaves the Unwatched filter it no longer belongs to.
        int revision = WatchedStore.revision(requireContext());
        if (revision != shownWatchRevision && getView() != null) showList();

        // Asked here as well as from showList: the rebuild that made this due may have happened
        // while the browser was in front, and arriving at the tab is the first moment it can be
        // put to somebody.
        offerGrowth();
    }

    @Override
    public void onDestroyView() {
        // A sheet outlives the view it was raised from, and a shown one still attached when the
        // window goes is a leaked window.
        if (moreSheet != null) moreSheet.dismiss();
        moreSheet = null;
        dismissCollectionSheet();
        // The tab bar is the activity's, and it is hidden on this screen's behalf. Left hidden,
        // the app would come back from a rotation with no way to reach Home.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setNavHiddenForSelection(false);
        }
        super.onDestroyView();
    }

    /**
     * The two things this screen asks for, at most one of them at a time.
     *
     * <p><b>Only while this tab is the one in front.</b> The library observer is bound to the view
     * lifecycle, and a pager keeps the tab beside the visible one STARTED — so a download finishing
     * while somebody is browsing rebuilds this list underneath them. Without this guard that either
     * burned the rate prompt's "not on cold open" rule on a rebuild nobody saw, or worse, raised a
     * sheet about downloads over the top of the browser.
     *
     * <p>One at a time, because the reminder card and the rate prompt both want this moment, and
     * two asks stacked on one screen is how an app teaches people to dismiss without reading.
     */
    private void offerGrowth() {
        if (!isResumed() || getView() == null) return;
        if (!RatePrompt.maybeAsk(requireContext())) offerReminders();
    }

    /**
     * Screen 16, panel D — the one place unwatched reminders can be turned on from a standing
     * start.
     *
     * <p>The notification is never sent unasked, so the asking has to happen somewhere the viewer
     * already is. Three unwatched videos is the threshold: one is a video somebody has not got to
     * yet, three is a backlog, and offering below that would be the app inventing a problem so it
     * can offer to solve it.
     *
     * <p>Asked once. Either button answers it for good — "No thanks" is an answer, not a deferral,
     * and a card that comes back next week is the reason people stop reading cards. Settings still
     * carries the switch for anyone who changes their mind.
     */
    private void offerReminders() {
        View card = getView() == null ? null : getView().findViewById(R.id.reminderOffer);
        if (card == null) return;

        int waiting = unwatchedCount();
        boolean ask = !SettingsPrefs.notifyUnwatched(requireContext())
                && !SettingsPrefs.reminderOfferMade(requireContext())
                && waiting >= REMINDER_THRESHOLD;
        card.setVisibility(ask ? View.VISIBLE : View.GONE);
        if (!ask) return;

        TextView body = card.findViewById(R.id.reminderOfferBody);
        if (body != null) body.setText(getString(R.string.reminder_offer_body, waiting));

        card.findViewById(R.id.reminderOfferYes).setOnClickListener(v -> {
            SettingsPrefs.setNotifyUnwatched(requireContext(), true);
            SettingsPrefs.setReminderOfferMade(requireContext());
            UnwatchedReminder.schedule(requireContext());
            card.setVisibility(View.GONE);
        });
        card.findViewById(R.id.reminderOfferNo).setOnClickListener(v -> {
            SettingsPrefs.setReminderOfferMade(requireContext());
            card.setVisibility(View.GONE);
        });
    }

    /** Finished videos nobody has played — the same field the dot and the filter read. */
    private int unwatchedCount() {
        int count = 0;
        for (DownloadEntity d : all) {
            if (d.status != DownloadStatus.COMPLETED) continue;
            if (TextUtils.isEmpty(d.outputUri)) continue;
            if (WatchedStore.isUnwatched(requireContext(), d.outputUri)) count++;
        }
        return count;
    }

    // ------------------------------------------------------------------ what is shown

    private void showList() {
        // Recorded here rather than in onResume so every route into a rebuild counts, not just
        // that one — otherwise a list rebuilt by the observer would still look stale to onResume.
        shownWatchRevision = WatchedStore.revision(requireContext());


        offerGrowth();

        List<DownloadEntity> shown = filtered();
        adapter.submit(requireContext(), shown, sort);

        // The count and size in the collection's header are read off the library, so they go
        // stale the moment one of its videos is deleted or another is filed into it.
        if (!TextUtils.isEmpty(collection) && collectionSubtitle != null) {
            collectionSubtitle.setText(collectionSummary());
        }

        boolean nothing = shown.isEmpty();
        list.setVisibility(nothing ? View.GONE : View.VISIBLE);
        empty.setVisibility(nothing ? View.VISIBLE : View.GONE);
        if (nothing) bindEmptyState();

        // After the rows exist, not before: the selection is made by matching against them.
        applyWatchedReview();
    }

    /**
     * Asks the library to open with everything watched already ticked — screen 13's one action.
     *
     * <p>Called from the storage screen before it starts this one. Left as a request rather than
     * carried out immediately because this fragment may not be created yet, and the tab it lives on
     * may not be the one in front.
     */
    public static void reviewWatchedWhenReady() {
        REVIEW_WATCHED.set(true);
    }

    /**
     * Carries out that request, once.
     *
     * <p>Taken with {@code getAndSet} so it happens on one pass and not on every later refresh — the
     * list rebinds whenever a download progresses, and re-ticking the watched set under somebody who
     * had just unticked two of them would be the app arguing with them.
     */
    private void applyWatchedReview() {
        if (!REVIEW_WATCHED.getAndSet(false)) return;
        if (all.isEmpty()) {
            // Nothing to tick yet — the library is still loading. Put the request back and wait for
            // the next pass rather than opening an empty selection.
            REVIEW_WATCHED.set(true);
            return;
        }

        Set<String> watched = new HashSet<>();
        long bytes = 0;
        for (DownloadEntity d : all) {
            if (d.status != DownloadStatus.COMPLETED || TextUtils.isEmpty(d.outputUri)) continue;
            if (WatchedStore.isUnwatched(requireContext(), d.outputUri)) continue;
            watched.add(d.outputUri);
            bytes += d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes;
        }
        if (watched.isEmpty()) return;

        int ticked = adapter.beginSelection(watched);
        if (ticked == 0) return;

        watchedBannerText.setText(getString(R.string.storage_preselected, Formats.bytes(bytes)));
        watchedBanner.setVisibility(View.VISIBLE);
    }

    /**
     * The two ways this list can be empty, which are not the same thing.
     *
     * <p>An empty library is somebody who has not downloaded anything, and the useful reply is to
     * show them how. A search that matched nothing is somebody who has plenty and typed the wrong
     * word — offering to teach them downloading there would be answering a question they did not
     * ask, so the action is dropped and the wording names what they searched for.
     */
    private void bindEmptyState() {
        // An empty collection is a third case, and it is the one with a way out: the library is
        // not empty and the search is not wrong — the scope is simply narrow. Teaching somebody
        // to download here would be answering a question they did not ask.
        if (!TextUtils.isEmpty(collection)) {
            emptyIcon.setImageResource(R.drawable.ic_folder);
            // Neutral: an empty collection is a narrow scope, not an invitation to do anything.
            paintEmptyMark(false);
            // Not the collection's name — the header two inches above already says it, and
            // hearing it twice makes the screen sound like it is insisting.
            emptyTitle.setText(R.string.collection_empty_title);
            emptyBody.setText(R.string.collection_empty_body);
            howToButton.setVisibility(View.GONE);
            return;
        }

        boolean searching = !TextUtils.isEmpty(query);
        emptyIcon.setImageResource(searching ? R.drawable.ic_search_off : R.drawable.ic_inbox);
        paintEmptyMark(!searching);
        emptyTitle.setText(searching
                ? getString(R.string.no_matches_for, query)
                : getString(R.string.no_downloads));
        emptyBody.setText(searching ? R.string.no_matches_body : R.string.no_downloads_body);
        howToButton.setVisibility(searching ? View.GONE : View.VISIBLE);
    }

    /**
     * The empty mark's two treatments, as the canvas draws them.
     *
     * <p>Accent for the state that leads somewhere — an empty library, with "How to download"
     * beneath it. Neutral for the states that only report: nothing matched, or this collection is
     * empty. Both are set on every pass rather than one being left to the layout, because the same
     * view serves all three and a mark left accent-red over "No matches" would read as an error.
     */
    private void paintEmptyMark(boolean inviting) {
        emptyIcon.setBackgroundResource(inviting
                ? R.drawable.ds_bg_block_accent : R.drawable.ds_bg_block_neutral);
        emptyIcon.setImageTintList(ContextCompat.getColorStateList(requireContext(),
                inviting ? R.color.ds_accent : R.color.ds_ink_faint));
    }

    /**
     * The library narrowed by the chip row and the search box, in that order.
     *
     * <p>Both narrow the same list rather than choosing between lists — which is why they are a
     * chip row and a header field, not tabs and a screen.
     */
    private List<DownloadEntity> filtered() {
        List<DownloadEntity> out = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.US);

        // Fetched once rather than asked per row: membership is a set lookup, and reading it
        // out of the store for every item would parse the same thing over and over.
        Set<String> inCollection = TextUtils.isEmpty(collection)
                ? null : CollectionStore.members(requireContext(), collection);

        for (DownloadEntity d : all) {
            if (inCollection != null
                    && (TextUtils.isEmpty(d.outputUri) || !inCollection.contains(d.outputUri))) {
                continue;
            }
            if (!filter.accepts(requireContext(), d)) continue;
            if (!needle.isEmpty()) {
                String title = d.title == null ? "" : d.title.toLowerCase(Locale.US);
                String name = d.fileName == null ? "" : d.fileName.toLowerCase(Locale.US);
                // The name as well as the title: smart naming means they usually agree, and
                // where they do not the viewer may remember either.
                if (!title.contains(needle) && !name.contains(needle)) continue;
            }
            out.add(d);
        }
        return out;
    }

    /** The header becomes a field and back again; the list underneath never moves. */
    private void setUpSearch(View view) {
        searchBar = view.findViewById(R.id.downloadsSearchBar);
        searchInput = view.findViewById(R.id.downloadsSearchInput);
        View clear = view.findViewById(R.id.btnSearchClear);

        view.findViewById(R.id.btnDownloadSearch).setOnClickListener(v -> showSearch(true));
        view.findViewById(R.id.btnSearchClose).setOnClickListener(v -> showSearch(false));
        clear.setOnClickListener(v -> searchInput.setText(""));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString();
                clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                showList();
            }
        });
    }

    private void showSearch(boolean searching) {
        header.setVisibility(searching ? View.GONE : View.VISIBLE);
        searchBar.setVisibility(searching ? View.VISIBLE : View.GONE);

        if (searching) {
            searchInput.requestFocus();
            InputMethodManager ime = ContextCompat.getSystemService(
                    requireContext(), InputMethodManager.class);
            if (ime != null) ime.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        } else {
            // Leaving the field clears what it was filtering by. A search left running behind a
            // header that no longer shows it is a list mysteriously missing most of its rows.
            searchInput.setText("");
            hideKeyboard();
        }
    }

    private void hideKeyboard() {
        InputMethodManager ime = ContextCompat.getSystemService(
                requireContext(), InputMethodManager.class);
        if (ime != null && searchInput != null) {
            ime.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
    }

    /** One chip per filter, built once. Exactly one is always selected. */
    private void setUpFilters(View view) {
        setUpCollections(view);

        filterChips = view.findViewById(R.id.filterChips);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        // Inserted at the front, in order, because Private and Collections are already in the
        // group: they are declared in the layout so they wrap with the filters rather than
        // scrolling away beside them, and appending would put the filters after them.
        for (int i = 0; i < DownloadFilter.values().length; i++) {
            DownloadFilter option = DownloadFilter.values()[i];
            // Inflated, not constructed — see item_filter_chip.xml for why.
            Chip chip = (Chip) inflater.inflate(R.layout.item_filter_chip, filterChips, false);
            chip.setText(option.label);
            chip.setTag(option);
            chip.setChecked(option == filter);
            filterChips.addView(chip, i);
        }
        filterChips.setOnCheckedStateChangeListener((g, ids) -> {
            // Only when a finger did it. Re-checking a chip to match the state is not a choice
            // being made, and treating it as one would undo whatever set that state.
            if (syncingChips) return;

            if (ids.isEmpty()) {
                // singleSelection lets the chosen chip be unchecked by tapping it again; the
                // list has to show something, so All is what "no filter" means.
                filter = DownloadFilter.ALL;
            } else {
                View chip = g.findViewById(ids.get(0));
                if (chip != null && chip.getTag() instanceof DownloadFilter) {
                    filter = (DownloadFilter) chip.getTag();
                }
            }
            // Belt and braces. An open collection hides this row entirely, so a chip cannot be
            // tapped from inside one — but if that ever changes, a filter and a collection both
            // narrowing at once is the confusion this prevents.
            collection = null;
            bindCollectionScope();
            showList();
        });
    }

    /**
     * The two collection chips — screen 08.
     *
     * <p>One opens the sheet; the other appears only while a collection is narrowing the list and
     * is the way back out of it. Both live outside the filter group, since a collection and a
     * filter are different questions and choosing one should not clear the other.
     */
    private void setUpCollections(View view) {
        collectionBar = view.findViewById(R.id.downloadsCollectionBar);
        collectionTitle = view.findViewById(R.id.collectionTitle);
        collectionSubtitle = view.findViewById(R.id.collectionSubtitle);

        Chip opener = view.findViewById(R.id.chipCollections);
        // The chevron is part of the chip, not a second control, so both halves do the one thing.
        opener.setOnClickListener(v -> showCollectionSheet());
        opener.setOnCloseIconClickListener(v -> showCollectionSheet());

        // The private folder — screen 11. Always on the row, because it is how the feature is
        // found: hiding the chip until a lock exists means the only way to discover the folder is
        // to go looking in Settings for something nobody has mentioned. Tapping it with no lock
        // set explains the folder and offers to turn it on; with one set, it asks for it.
        privateChip = view.findViewById(R.id.chipPrivate);
        privateChip.setOnClickListener(v -> privateAuth.require(requireActivity(),
                R.string.lock_reason_open, () -> PrivateFolderActivity.open(requireContext())));

        view.findViewById(R.id.btnCollectionBack)
                .setOnClickListener(v -> chooseCollection(null));
        view.findViewById(R.id.btnCollectionMore)
                .setOnClickListener(this::showCollectionMenu);

        bindCollectionScope();
    }

    /**
     * Rename and delete, on the collection this header names — screen 08.
     *
     * <p>A popup rather than a sheet: two items, both about the thing already on screen, and a
     * sheet would be a bigger gesture than either deserves.
     */
    private void showCollectionMenu(View anchor) {
        if (TextUtils.isEmpty(collection)) return;

        PopupMenu menu = new PopupMenu(requireContext(), anchor);
        menu.getMenu().add(0, 1, 0, R.string.rename);
        menu.getMenu().add(0, 2, 1, R.string.delete);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) askCollectionName();
            else confirmDeleteCollection();
            return true;
        });
        menu.show();
    }

    /** Renames the open collection, keeping the list scoped to it under its new name. */
    private void askCollectionName() {
        String from = collection;
        if (TextUtils.isEmpty(from)) return;

        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_rename, null, false);
        EditText input = content.findViewById(R.id.renameInput);
        // A collection has no extension to protect, so the chip and its note stay hidden.
        input.setText(from);
        input.setSelection(input.getText().length());

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.rename)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String typed = input.getText() == null ? "" : input.getText().toString().trim();
                    if (typed.isEmpty() || typed.equals(from)) return;

                    if (!CollectionStore.rename(requireContext(), from, typed)) {
                        snack(getString(R.string.collection_name_taken));
                        return;
                    }
                    // Follows the rename rather than dropping out of it: the viewer is looking
                    // at this collection, and renaming it is not leaving it.
                    collection = typed;
                    bindCollectionScope();
                    showList();
                })
                .show();
    }

    /**
     * Removes the collection and keeps every video in it.
     *
     * <p>The confirm says so in those words, with the real count, because that is the one thing
     * somebody needs to know before tapping Delete on a folder full of videos — and the answer
     * is not the one the word "delete" implies.
     */
    private void confirmDeleteCollection() {
        String name = collection;
        if (TextUtils.isEmpty(name)) return;

        int count = CollectionStore.count(requireContext(), name);
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(getString(R.string.collection_delete_title, name))
                .setMessage(getResources().getQuantityString(
                        R.plurals.collection_delete_message, count, count))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    CollectionStore.delete(requireContext(), name);
                    chooseCollection(null);
                    snack(getString(R.string.collection_deleted, name));
                })
                .show();
    }

    // ------------------------------------------------------------------ select mode

    /**
     * Screen 07's selection, wired once.
     *
     * <p>The adapter owns which rows are chosen; this owns the header and the action bar that
     * describe them, and hears about changes through {@link #onSelectionChanged}.
     */
    private void setUpSelectMode(View view) {
        selectBar = view.findViewById(R.id.downloadsSelectBar);
        selectCount = view.findViewById(R.id.selectCount);
        selectActions = view.findViewById(R.id.selectActions);
        filterRow = view.findViewById(R.id.downloadFilters);
        watchedBanner = view.findViewById(R.id.watchedBanner);
        watchedBannerText = view.findViewById(R.id.watchedBannerText);

        view.findViewById(R.id.btnSelectClose).setOnClickListener(v -> adapter.clearSelection());
        view.findViewById(R.id.btnSelectAll).setOnClickListener(v -> adapter.selectAll());

        view.findViewById(R.id.actionShare).setOnClickListener(v -> shareSelection());
        view.findViewById(R.id.actionCollection).setOnClickListener(v -> fileSelection());
        view.findViewById(R.id.actionPrivate).setOnClickListener(v -> askPrivateSelection());
        view.findViewById(R.id.actionDelete).setOnClickListener(v -> confirmDeleteSelection());

        // The bar sits where the tab bar was, so it inherits the tab bar's problem: the window
        // extends under the gesture area and the activity deliberately pads nothing at the
        // bottom. BottomNavigationView solves this for itself; this has to be told.
        //
        // Padding rather than a margin, so the surface still reaches the bottom of the screen
        // and the gesture pill sits on it rather than on the list showing through beneath.
        ViewCompat.setOnApplyWindowInsetsListener(selectActions, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            return insets;
        });
        // Asked for explicitly: the window's first inset pass has usually already happened by
        // the time this fragment's view exists, and a listener attached afterwards is never
        // called on its own. Without this the padding only appears after a rotation.
        ViewCompat.requestApplyInsets(selectActions);

        // Back unwinds this screen's own state before it leaves the screen: the selection first,
        // then the open collection. Anything else would throw away a dozen taps to answer a
        // gesture that meant "not this". Off entirely when neither is up, so back means what it
        // usually means the rest of the time.
        selectBack = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (adapter.isSelecting()) adapter.clearSelection();
                else chooseCollection(null);
            }
        };
        requireActivity().getOnBackPressedDispatcher()
                .addCallback(getViewLifecycleOwner(), selectBack);
    }

    private void updateBackHandling() {
        if (selectBack == null) return;
        selectBack.setEnabled(adapter.isSelecting() || !TextUtils.isEmpty(collection));
    }

    @Override
    public void onSelectionChanged(int count, boolean selecting) {
        if (getView() == null) return;

        // A search still running behind the select bar would leave the list short for a reason
        // nothing on screen explains, so it is closed rather than merely hidden — and closing it
        // is what clears the query.
        if (selecting) showSearch(false);

        selectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        selectActions.setVisibility(selecting ? View.VISIBLE : View.GONE);
        updateBackHandling();
        if (selecting) {
            // The other headers and the chip row all describe browsing, which is not what is
            // happening while a selection is open.
            header.setVisibility(View.GONE);
            collectionBar.setVisibility(View.GONE);
            filterRow.setVisibility(View.GONE);
        } else {
            // The banner explains a selection. Once there is no selection there is nothing left for
            // it to explain, and a line about pre-ticked rows over an ordinary list is a puzzle.
            watchedBanner.setVisibility(View.GONE);
            // Back to whichever of the two was up before — the library's header, or a
            // collection's. Asking the scope rather than assuming, or leaving a selection made
            // inside Workout would drop the viewer back into the whole library.
            bindCollectionScope();
        }

        selectCount.setText(count == 0
                ? getString(R.string.select_none)
                : getResources().getQuantityString(R.plurals.selected_count, count, count));

        // Nothing chosen is a real state — the last tick can be taken off — and the actions stay
        // in place greyed rather than vanishing, so the bar does not jump under a moving thumb.
        boolean any = count > 0;
        selectActions.setAlpha(any ? 1f : 0.4f);
        setSelectActionsEnabled(any);

        // The tab bar steps aside so Delete never ends up beside Home.
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setNavHiddenForSelection(selecting);
        }
    }

    private void setSelectActionsEnabled(boolean enabled) {
        ViewGroup bar = (ViewGroup) selectActions;
        for (int i = 0; i < bar.getChildCount(); i++) {
            bar.getChildAt(i).setEnabled(enabled);
        }
    }

    /**
     * Hands several videos to whatever the viewer picks.
     *
     * <p>One chooser for the lot rather than one per file, which is the only thing that makes a
     * batch share worth having.
     */
    private void shareSelection() {
        List<DownloadEntity> chosen = adapter.selection();
        if (chosen.isEmpty()) return;

        ArrayList<Uri> uris = new ArrayList<>();
        String mime = null;
        for (DownloadEntity d : chosen) {
            if (TextUtils.isEmpty(d.outputUri)) continue;
            uris.add(Uri.parse(d.outputUri));
            // A mixed bag of video and audio has no one type; "*/*" is the honest answer, and
            // claiming video/* would hide the audio files from half the targets.
            if (mime == null) mime = d.mime;
            else if (!mime.equals(d.mime)) mime = "*/*";
        }
        if (uris.isEmpty()) return;

        Intent send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType(TextUtils.isEmpty(mime) ? "video/*" : mime)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_videos)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** Files the whole selection at once — the add mode of screen 08's sheet. */
    private void fileSelection() {
        List<DownloadEntity> chosen = adapter.selection();
        if (chosen.isEmpty()) return;

        List<String> uris = new ArrayList<>();
        for (DownloadEntity d : chosen) {
            if (!TextUtils.isEmpty(d.outputUri)) uris.add(d.outputUri);
        }
        if (uris.isEmpty()) return;

        dismissCollectionSheet();
        collectionSheet = CollectionSheet.add(requireContext(), uris,
                new CollectionSheet.Listener() {
                    @Override
                    public void onCollectionChosen(@Nullable String name) {
                        // Add mode never chooses a scope.
                    }

                    @Override
                    public void onCollectionsFiled(String message) {
                        if (!isAdded()) return;
                        adapter.clearSelection();
                        snack(message);
                        showList();
                    }
                });
        collectionSheet.withLibrary(all).show();
    }

    /**
     * The one action here that cannot be undone, so it says what it will do and counts it.
     *
     * <p>The count comes from the selection rather than a stored number: between the tap and the
     * dialog the library can refresh, and a confirm that promises three when it will delete two
     * has told the viewer something false at the worst possible moment.
     */
    private void confirmDeleteSelection() {
        List<DownloadEntity> chosen = adapter.selection();
        if (chosen.isEmpty()) return;

        int count = chosen.size();
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(getResources().getQuantityString(
                        R.plurals.delete_videos_title, count, count))
                .setMessage(R.string.delete_videos_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> deleteSelection(chosen))
                .show();
    }

    /**
     * Carries out a confirmed batch delete.
     *
     * <p>Files the app owns go immediately; anything the system will not let it touch — saved by
     * a previous install — goes into one consent dialog naming all of them, rather than one
     * dialog per file, which is what made deleting a selection impossible before.
     */
    private void deleteSelection(List<DownloadEntity> chosen) {
        MediaLibrary.BatchResult result = App.get().repository().deleteAll(chosen);
        adapter.clearSelection();
        forget(result.deleted);

        if (result.confirmation != null) {
            // From Android 11 the system's own request carries the delete out on approval, so
            // there is nothing to run afterwards — only the side stores to tidy, which the
            // library refresh below does by reconciling against what is actually there.
            afterConfirmation = null;
            if (launchConfirmation(result.confirmation)) return;
        }
        reportDeleted(result);
    }

    /** Says what happened, in one line, and only where it is not obvious. */
    private void reportDeleted(MediaLibrary.BatchResult result) {
        if (getView() == null) return;

        int gone = result.deleted.size();
        int stuck = result.failed.size();
        if (stuck == 0 && gone == 0) return;

        snack(stuck == 0
                ? getResources().getQuantityString(R.plurals.deleted_count, gone, gone)
                : getString(R.string.deleted_some, gone, stuck));
    }

    /**
     * A passing remark, above whatever is at the foot of the screen.
     *
     * <p>Anchored to the select bar while one is up and to the tab bar otherwise, so it never
     * lands on the actions it is reporting on. Coloured by hand for the same reason the
     * activity's notice is: this screen's snackbar is built against a context still on the MVP
     * palette, and the ds_snackbar_* tokens are the pair that has a dark counterpart.
     */
    private void snack(CharSequence text) {
        if (getView() == null || TextUtils.isEmpty(text)) return;
        Snacks.make(requireView(), text, Snackbar.LENGTH_LONG, selectActions).show();
    }

    /**
     * The visible list, as a queue the player can walk — screen 09, panel C.
     *
     * <p>Only finished files: a transfer still arriving has nothing to play, and a queue that
     * stops dead on one would make Next look broken.
     */
    private List<PlayerQueue.Item> playableQueue() {
        List<PlayerQueue.Item> out = new ArrayList<>();
        for (DownloadEntity d : filtered()) {
            if (d.status != DownloadStatus.COMPLETED || TextUtils.isEmpty(d.outputUri)) continue;
            long size = d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes;
            out.add(PlayerQueue.item(d.outputUri, d.title, d.durationMs, size, d.posterUrl));
        }
        return out;
    }

    /**
     * What the list is narrowed by, in the viewer's own words.
     *
     * <p>An open collection first, then a search, then a filter chip — the order somebody would
     * describe it in themselves. Empty when nothing is narrowing it, which the sheet words as
     * "your downloads" rather than naming a filter that is not on.
     */
    @Nullable
    private String scopeLabel() {
        if (!TextUtils.isEmpty(collection)) return collection;
        if (!TextUtils.isEmpty(query)) return query;
        return filter == DownloadFilter.ALL ? null : getString(filter.label);
    }

    /** Every saved file the library currently holds, for reconciling the side stores against. */
    private Set<String> liveUris() {
        Set<String> uris = new HashSet<>();
        for (DownloadEntity d : all) {
            if (!TextUtils.isEmpty(d.outputUri)) uris.add(d.outputUri);
        }
        return uris;
    }

    /** Takes gone files out of the two side stores that still name them. */
    private void forget(List<String> uris) {
        for (String uri : uris) {
            CollectionStore.forget(requireContext(), uri);
            WatchedStore.forget(requireContext(), uri);
        }
    }

    private void showCollectionSheet() {
        dismissCollectionSheet();
        collectionSheet = CollectionSheet.filter(requireContext(), new CollectionSheet.Listener() {
            @Override
            public void onCollectionChosen(@Nullable String name) {
                chooseCollection(name);
            }

            @Override
            public void onCollectionsFiled(String message) {
                // Filter mode never files anything; nothing to say.
            }
        });
        collectionSheet.withLibrary(all).show();
    }

    /**
     * Scopes the list to one collection, or to none.
     *
     * <p>Choosing a collection puts the filter row back to All, for the same reason the reverse
     * clears the collection: the chips are one row and read as one choice. Looking at Workout
     * while Unwatched is also lit is two narrowings with one visible reason, and the count that
     * comes back is a puzzle rather than an answer.
     */
    private void chooseCollection(@Nullable String name) {
        collection = name;
        if (name != null && filter != DownloadFilter.ALL) {
            filter = DownloadFilter.ALL;
            checkFilterChip(DownloadFilter.ALL);
        }
        bindCollectionScope();
        showList();
    }

    /** Moves the tick to a chip without the listener reading it as somebody's choice. */
    private void checkFilterChip(DownloadFilter option) {
        if (filterChips == null) return;
        syncingChips = true;
        for (int i = 0; i < filterChips.getChildCount(); i++) {
            View chip = filterChips.getChildAt(i);
            if (chip instanceof Chip) {
                ((Chip) chip).setChecked(chip.getTag() == option);
            }
        }
        syncingChips = false;
    }

    /**
     * Swaps the library's header for the open collection's, and back — screen 08, panel D.
     *
     * <p>The chip row goes with it. Inside one collection the filters would be asking about a
     * list that is already narrowed, and "All" would mean all of Workout rather than all of the
     * library — the same word for two different things on the same screen.
     */
    private void bindCollectionScope() {
        boolean scoped = !TextUtils.isEmpty(collection);
        if (collectionBar == null) return;

        collectionBar.setVisibility(scoped ? View.VISIBLE : View.GONE);
        if (header != null) header.setVisibility(scoped ? View.GONE : View.VISIBLE);
        if (filterRow != null) filterRow.setVisibility(scoped ? View.GONE : View.VISIBLE);
        updateBackHandling();
        if (!scoped) return;

        collectionTitle.setText(collection);
        collectionSubtitle.setText(collectionSummary());
    }

    /** "12 videos · 214 MB" — what is in here, and what it is costing. */
    private String collectionSummary() {
        Set<String> members = CollectionStore.members(requireContext(), collection);
        int count = 0;
        long bytes = 0;
        for (DownloadEntity d : all) {
            if (TextUtils.isEmpty(d.outputUri) || !members.contains(d.outputUri)) continue;
            count++;
            bytes += d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes;
        }

        String videos = getResources().getQuantityString(R.plurals.videos_count, count, count);
        // The size is dropped rather than shown as "0 B" when nothing here has one — an empty
        // collection is a real state and does not need a number to describe its weight.
        return bytes > 0 ? videos + " · " + Formats.bytes(bytes) : videos;
    }

    private void dismissCollectionSheet() {
        if (collectionSheet != null) collectionSheet.dismiss();
        collectionSheet = null;
    }

    /**
     * Rows or tiles.
     *
     * <p>The day headings span the whole width either way, which is what the span lookup is for — a
     * heading squeezed into one column of two would read as a row of the list rather than as a line
     * drawn across it.
     */
    private void applyMode(DownloadAdapter.Mode mode) {
        adapter.setMode(mode);

        if (mode == DownloadAdapter.Mode.GRID) {
            GridLayoutManager grid = new GridLayoutManager(requireContext(), GRID_COLUMNS);
            grid.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    // Headings and running transfers both take the full width — a heading
                    // squeezed into one column reads as a row of the list rather than a line
                    // drawn across it, and a transfer needs the room for its rate and total.
                    return adapter.isFullWidth(position) ? GRID_COLUMNS : 1;
                }
            });
            list.setLayoutManager(grid);
        } else {
            list.setLayoutManager(new LinearLayoutManager(requireContext()));
        }

        // The button offers the other layout rather than announcing the current one: it is a
        // switch, and a switch that shows where you are gives you nothing to press towards.
        layoutToggle.setImageResource(mode == DownloadAdapter.Mode.GRID
                ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
        DownloadPrefs.setMode(requireContext(), mode);
    }

    /**
     * What order to put the list in.
     *
     * <p>One choice at a time, because these are alternatives in a way filters are not — a list has
     * exactly one order, and offering four checkboxes would invite a combination that means nothing.
     */
    private void chooseSort() {
        DownloadSort[] options = DownloadSort.values();
        String[] labels = new String[options.length];
        int current = 0;
        for (int i = 0; i < options.length; i++) {
            labels[i] = getString(options[i].label);
            if (options[i] == sort) current = i;
        }

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.sort_downloads)
                .setSingleChoiceItems(labels, current, (dialog, which) -> {
                    sort = options[which];
                    DownloadPrefs.setSort(requireContext(), sort);
                    showList();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------- row actions

    @Override
    public void pause(DownloadEntity d) {
        DownloadService.control(requireContext(), DownloadService.ACTION_PAUSE, d.id);
    }

    @Override
    public void resume(DownloadEntity d) {
        DownloadService.control(requireContext(), DownloadService.ACTION_RESUME, d.id);
    }

    /**
     * Stops a transfer for good.
     *
     * <p>Asked first, unlike pause. Pausing is free to undo and cancelling is not — the bytes
     * already fetched are thrown away with the scratch space, and on a long download that is
     * somebody's evening. The confirm says how far along it was, because that is the fact that
     * decides it.
     */
    @Override
    public void cancel(DownloadEntity d) {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.cancel_download_title)
                .setMessage(getString(R.string.cancel_download_message, d.title))
                .setNegativeButton(R.string.keep_downloading, null)
                .setPositiveButton(R.string.cancel_download, (dialog, which) ->
                        DownloadService.control(requireContext(),
                                DownloadService.ACTION_CANCEL, d.id))
                .show();
    }

    @Override
    public void open(DownloadEntity d) {
        if (TextUtils.isEmpty(d.outputUri)) return;
        if (!App.get().repository().library().exists(d.outputUri)) {
            // Removed from the gallery behind our back. Say so, and let the refreshed list drop
            // the row rather than handing the player a uri that resolves to nothing.
            Toast.makeText(requireContext(), R.string.video_missing, Toast.LENGTH_SHORT).show();
            App.get().repository().refreshLibrary();
            return;
        }
        // The player is handed the list exactly as it is on screen — same order, same filter,
        // same collection, same search. That is what makes its "next" mean what the viewer
        // would expect rather than whatever the library happens to hold.
        PlayerActivity.open(requireContext(), d.outputUri, d.title, playableQueue(), scopeLabel());
    }

    /**
     * Everything else one row can do.
     *
     * <p>Behind a menu rather than along the row, because these four differ from playing in kind:
     * playing is what the row is for, and each of these is a decision about the file. Sharing and
     * renaming and reading its details are only offered for a video that is actually there —
     * a transfer still running has nothing to share and no name of its own yet.
     */
    @Override
    public void more(DownloadEntity d) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.sheet_download_more, null, false);

        ((TextView) content.findViewById(R.id.moreName))
                .setText(TextUtils.isEmpty(d.fileName) ? d.title : d.fileName);
        ((TextView) content.findViewById(R.id.moreMeta)).setText(metaLine(d));

        ImageView sheetThumb = content.findViewById(R.id.moreThumb);
        if (d.kind == MediaKind.AUDIO) {
            Thumbnails.audio(sheetThumb);
        } else {
            sheetThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Thumbnails.load(sheetThumb, d.posterUrl, d.headers());
        }

        boolean saved = d.status == DownloadStatus.COMPLETED && !TextUtils.isEmpty(d.outputUri);
        View play = content.findViewById(R.id.morePlay);
        View share = content.findViewById(R.id.moreShare);
        View rename = content.findViewById(R.id.moreRename);
        View collect = content.findViewById(R.id.moreCollection);
        play.setVisibility(saved ? View.VISIBLE : View.GONE);
        share.setVisibility(saved ? View.VISIBLE : View.GONE);
        rename.setVisibility(saved ? View.VISIBLE : View.GONE);
        collect.setVisibility(saved ? View.VISIBLE : View.GONE);

        moreSheet = new BottomSheetDialog(requireContext(),
                R.style.ThemeOverlay_Ds_BottomSheet);
        moreSheet.setContentView(content);

        play.setOnClickListener(v -> {
            dismissMore();
            open(d);
        });
        share.setOnClickListener(v -> {
            dismissMore();
            share(d);
        });
        rename.setOnClickListener(v -> {
            dismissMore();
            askNewName(d);
        });
        // The same sheet either way, but the row has to say which job it is doing. A video already
        // filed somewhere is managed from here — including taken out of one collection and left in
        // another — and a row that only ever said "Add to collection" is a row nobody would open
        // looking for the way out.
        ((TextView) collect).setText(CollectionStore.holds(requireContext(), d.outputUri)
                ? R.string.collection_manage_video : R.string.collection_add_video);
        collect.setOnClickListener(v -> {
            dismissMore();
            fileIntoCollection(d);
        });

        // Screen 11. Offered for anything saved, lock or no lock: setting one up is part of the
        // action rather than a prerequisite for seeing it — see askMoveToPrivate.
        View toPrivate = content.findViewById(R.id.morePrivate);
        toPrivate.setVisibility(saved ? View.VISIBLE : View.GONE);
        toPrivate.setOnClickListener(v -> {
            dismissMore();
            askMoveToPrivate(d);
        });
        content.findViewById(R.id.moreProperty).setOnClickListener(v -> {
            dismissMore();
            showProperties(d);
        });
        content.findViewById(R.id.moreDelete).setOnClickListener(v -> {
            dismissMore();
            confirmDelete(d);
        });

        moreSheet.show();
    }

    private void dismissMore() {
        if (moreSheet != null) moreSheet.dismiss();
        moreSheet = null;
    }

    /**
     * "1.2 MB • 720p • 0:30" — the same line the row carries.
     *
     * <p>Deliberately the same shape in both places: the sheet is raised from the row, and a
     * different summary of the same file arriving on top of it would read as a different file.
     */
    private String metaLine(DownloadEntity d) {
        long size = d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes;
        return Formats.bytes(size)
                + (TextUtils.isEmpty(d.quality) ? "" : " • " + d.quality)
                + (d.durationMs > 0 ? " • " + Formats.duration(d.durationMs) : "");
    }

    /**
     * The private folder, from the ⋮ sheet — screen 11.
     *
     * <p>The credential comes first, and setting one up counts: somebody who has never turned the
     * lock on can still make a video private from here, and the intro plus setup happen in front of
     * the move rather than being a trip to Settings first. See {@link PrivateAuth}.
     */
    private void askMoveToPrivate(DownloadEntity d) {
        privateAuth.require(requireActivity(), R.string.lock_reason_move, () -> moveToPrivate(d));
    }

    /** The same, for a whole selection — screen 07's action bar. */
    private void askPrivateSelection() {
        List<DownloadEntity> chosen = adapter.selection();
        if (chosen.isEmpty()) return;
        privateAuth.require(requireActivity(), R.string.lock_reason_move_many,
                () -> moveSelectionToPrivate(chosen));
    }

    /**
     * Moves several videos into the private folder in one pass.
     *
     * <p>Copy every one first, then remove the originals in a <em>single</em> batch. One request
     * rather than one per file, because anything the app does not own needs the system's consent
     * and a dozen consent dialogs in a row is the bug that made deleting a selection impossible
     * before — see {@link #deleteSelection}.
     *
     * <p>Which means the copies have to be checked afterwards rather than trusted: the viewer can
     * refuse that one consent request, and a copy left behind an original that survived is the same
     * video twice, one of them invisible. So every original is looked at again when the request
     * returns, whatever the answer, and any copy whose original is still there is undone.
     */
    private void moveSelectionToPrivate(List<DownloadEntity> chosen) {
        List<DownloadEntity> movable = new ArrayList<>();
        for (DownloadEntity d : chosen) {
            if (d.status == DownloadStatus.COMPLETED && !TextUtils.isEmpty(d.outputUri)) {
                movable.add(d);
            }
        }
        if (movable.isEmpty()) {
            snack(getString(R.string.private_move_none));
            return;
        }

        snack(getString(R.string.private_moving));
        final Context app = requireContext().getApplicationContext();

        privateIo.execute(() -> {
            // Row to copy, so a copy can be undone if its original will not go.
            final Map<DownloadEntity, PrivateStore.Item> copies = new LinkedHashMap<>();
            for (DownloadEntity d : movable) {
                PrivateStore.Item item = PrivateStore.moveIn(app, d);
                if (item != null) copies.put(d, item);
            }

            postToView(() -> {
                if (copies.isEmpty()) {
                    snack(getString(R.string.private_move_failed));
                    return;
                }

                List<DownloadEntity> copied = new ArrayList<>(copies.keySet());
                MediaLibrary.BatchResult result = App.get().repository().deleteAll(copied);
                adapter.clearSelection();
                forget(result.deleted);

                if (result.confirmation != null) {
                    // Checked on the way back either way: approved deletes the originals, refused
                    // leaves them, and only the second of those needs the copies undoing.
                    onConfirmationReturn = () -> settlePrivateBatch(app, copies);
                    afterConfirmation = null;
                    if (launchConfirmation(result.confirmation)) return;
                    onConfirmationReturn = null;
                }
                settlePrivateBatch(app, copies);
            });
        });
    }

    /**
     * Keeps the copies whose originals are gone, and undoes the rest.
     *
     * <p>Asked of the library rather than assumed from a result code: what matters is whether the
     * file is still visible to everything else, and that is a question with an answer.
     */
    private void settlePrivateBatch(Context app, Map<DownloadEntity, PrivateStore.Item> copies) {
        // On the io thread: each check is a MediaStore query and the tidying is file deletion.
        privateIo.execute(() -> {
            MediaLibrary library = App.get().repository().library();
            int moved = 0;
            int stuck = 0;
            for (Map.Entry<DownloadEntity, PrivateStore.Item> entry : copies.entrySet()) {
                if (library.exists(entry.getKey().outputUri)) {
                    PrivateStore.delete(app, entry.getValue());
                    stuck++;
                } else {
                    moved++;
                }
            }

            final int done = moved;
            final int refused = stuck;
            final int total = copies.size();
            // One video is named; several are counted. A name is the more useful of the two when
            // there is one to give, and four of them would not fit on a snackbar.
            final String only = total == 1
                    ? copies.keySet().iterator().next().title : null;
            postToView(() -> {
                if (done == 0) {
                    snack(getString(R.string.private_move_failed));
                } else if (refused > 0) {
                    snack(getString(R.string.private_move_failed_n, refused, total));
                } else if (only != null) {
                    snack(getString(R.string.private_moved_in, only));
                } else {
                    snack(getString(R.string.private_moved_in_n, done));
                }
                App.get().repository().refreshLibrary();
            });
        });
    }

    /**
     * Hides one video in the private folder — screen 11.
     *
     * <p>The same path as a selection of many, deliberately. It used to have its own, which removed
     * the original with {@code mayAsk = false} — so a video this app does not own, one saved by a
     * previous install or by the gallery, could not be moved at all: the delete needed the system's
     * consent, was never allowed to ask for it, and the move undid itself and reported a failure
     * the viewer could do nothing about. The batch path already asks, waits, and checks afterwards.
     */
    private void moveToPrivate(DownloadEntity d) {
        moveSelectionToPrivate(Collections.singletonList(d));
    }

    /** Runs on the main thread, and only while there is still a screen to run against. */
    private void postToView(Runnable action) {
        View view = getView();
        if (view != null) view.post(() -> {
            if (isAdded()) action.run();
        });
    }

    /** The same sheet as the chip, in its other mode: pick where this video is filed. */
    private void fileIntoCollection(DownloadEntity d) {
        if (TextUtils.isEmpty(d.outputUri)) return;

        dismissCollectionSheet();
        collectionSheet = CollectionSheet.add(requireContext(),
                Collections.singletonList(d.outputUri), new CollectionSheet.Listener() {
                    @Override
                    public void onCollectionChosen(@Nullable String name) {
                        // Add mode never chooses a scope.
                    }

                    @Override
                    public void onCollectionsFiled(String message) {
                        if (!isAdded()) return;
                        snack(message);
                        // The counts on the chip sheet changed, and so may this list if it is
                        // currently scoped to one of the collections just added to.
                        showList();
                    }
                });
        collectionSheet.withLibrary(all).show();
    }

    /**
     * Hands the video to whatever the viewer picks.
     *
     * <p>A chooser every time, never a remembered target. Which app a video should go to is a
     * decision about that video, and this app has no business having an opinion about it.
     */
    private void share(DownloadEntity d) {
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(TextUtils.isEmpty(d.mime) ? "video/*" : d.mime)
                .putExtra(Intent.EXTRA_STREAM, Uri.parse(d.outputUri))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Asks for a new name, starting from the one it has.
     *
     * <p>Without the extension, and the extension is put back by the library: it is not part of
     * what anyone means by the name of a video, and a typed ".mp4" that replaced a real ".mkv"
     * would leave a file nothing plays.
     */
    /**
     * Renames the stem and leaves the extension alone — screen 07.
     *
     * <p>The suffix is shown as a fixed chip rather than hidden or editable. Hidden, people
     * wonder whether they are supposed to type it and produce "clip.mp4.mp4"; editable, a
     * single slip renames a video into something no player will open. Showing it and refusing
     * to change it is the only version that cannot go wrong.
     */
    private void askNewName(DownloadEntity d) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_rename, null, false);
        EditText input = content.findViewById(R.id.renameInput);
        TextView suffix = content.findViewById(R.id.renameExtension);
        View hint = content.findViewById(R.id.renameHint);

        String current = TextUtils.isEmpty(d.fileName) ? d.title : d.fileName;
        String extension = extensionOf(current);
        String stem = TextUtils.isEmpty(extension)
                ? current : current.substring(0, current.length() - extension.length());

        input.setText(stem);
        input.setSelection(input.getText().length());
        // No extension to protect means nothing to explain, and an empty chip beside the field
        // would be a question with no answer.
        boolean hasExtension = !TextUtils.isEmpty(extension);
        suffix.setVisibility(hasExtension ? View.VISIBLE : View.GONE);
        hint.setVisibility(hasExtension ? View.VISIBLE : View.GONE);
        if (hasExtension) suffix.setText(extension);

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.rename)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.save, (dialog, which) -> {
                    String typed = input.getText() == null ? "" : input.getText().toString().trim();
                    if (typed.isEmpty()) return;
                    // The extension is put back rather than trusted from the field, so what is
                    // saved carries it whatever was typed.
                    performRename(d, typed + extension, true);
                })
                .show();
    }

    /**
     * The trailing ".mp4", including the dot, or empty when there is not one.
     *
     * <p>Bounded on purpose. A dot late in a long title is part of the sentence, not a suffix,
     * and treating "Ep. 4" as an extension would rename the file to "Ep".
     */
    private static String extensionOf(String name) {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";

        String suffix = name.substring(dot);
        if (suffix.length() > 6) return "";
        for (int i = 1; i < suffix.length(); i++) {
            if (!Character.isLetterOrDigit(suffix.charAt(i))) return "";
        }
        return suffix;
    }

    /**
     * @param mayAsk false on the second attempt, so a refusal cannot put the same request up
     *               again and again
     */
    private void performRename(DownloadEntity d, String newName, boolean mayAsk) {
        if (TextUtils.isEmpty(newName.trim())) return;

        MediaLibrary.WriteResult result = App.get().repository().rename(d, newName);
        if (result.done) return;

        // Granting access is not the rename; it only makes the rename possible. So the retry is
        // queued before the request goes up, and runs when the viewer comes back having agreed.
        if (mayAsk && result.confirmation != null) {
            afterConfirmation = () -> performRename(d, newName, false);
            if (launchConfirmation(result)) return;
            afterConfirmation = null;
        }
        Toast.makeText(requireContext(), R.string.rename_failed, Toast.LENGTH_SHORT).show();
    }

    /** Everything the app knows about one file, in the order somebody would ask for it. */
    /**
     * Everything known about one file — screen 07.
     *
     * <p>Two columns rather than the run of "Label\nValue" this used to be, so values line up
     * and a size can be compared against a size. Rows that have no value are left out entirely:
     * a blank beside "Quality" says the app failed to read it, which is not what it means.
     */
    private void showProperties(DownloadEntity d) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_properties, null, false);
        ViewGroup rows = content.findViewById(R.id.propertyRows);

        addProperty(rows, R.string.property_name,
                TextUtils.isEmpty(d.fileName) ? d.title : d.fileName, null);
        addProperty(rows, R.string.property_size, Formats.bytes(
                d.totalBytes > 0 ? d.totalBytes : d.downloadedBytes), null);
        if (d.durationMs > 0) {
            addProperty(rows, R.string.property_duration, Formats.duration(d.durationMs), null);
        }
        if (!TextUtils.isEmpty(d.quality)) {
            addProperty(rows, R.string.property_quality, d.quality, null);
        }
        addProperty(rows, R.string.property_type,
                TextUtils.isEmpty(d.mime) ? getString(R.string.kind_video) : d.mime, null);

        long when = d.completedAt > 0 ? d.completedAt : d.createdAt;
        if (when > 0) {
            addProperty(rows, R.string.property_date,
                    DateUtils.formatDateTime(requireContext(), when,
                            DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                                    | DateUtils.FORMAT_ABBREV_MONTH | DateUtils.FORMAT_SHOW_YEAR),
                    null);
        }
        // A link back, per the design: the page is usually where somebody wants to go next —
        // to watch the rest of it, or to fetch a fresh link for one that expired.
        if (!TextUtils.isEmpty(d.pageUrl)) {
            addProperty(rows, R.string.property_source, hostOf(d.pageUrl),
                    () -> openPage(d.pageUrl));
        }
        // The address the media itself came from, which is the one that answers "why has this no
        // sound" — a video-only rendition and a complete file look identical once saved.
        if (!TextUtils.isEmpty(d.sourceUrl)) {
            addProperty(rows, R.string.property_stream, d.sourceUrl, null);
        }
        if (!TextUtils.isEmpty(d.outputUri)) {
            addProperty(rows, R.string.property_location, d.outputUri, null);
        }

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.property)
                .setView(content)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** @param onTap null for a plain fact; set for one that leads somewhere. */
    private void addProperty(ViewGroup rows, @StringRes int label, String value,
                             @Nullable Runnable onTap) {
        View row = LayoutInflater.from(rows.getContext())
                .inflate(R.layout.item_property, rows, false);
        ((TextView) row.findViewById(R.id.propertyLabel)).setText(label);

        TextView valueView = row.findViewById(R.id.propertyValue);
        valueView.setText(value);
        if (onTap != null) {
            // Accent and tappable together. A coloured line that does nothing is a broken link,
            // and a tappable one that looks like the rest is one nobody finds.
            valueView.setTextColor(ContextCompat.getColor(rows.getContext(), R.color.ds_accent));
            row.setOnClickListener(v -> onTap.run());
        }
        rows.addView(row);
    }

    /** The site, not the whole address — a query string tells nobody where a video came from. */
    private static String hostOf(String url) {
        String host = Uri.parse(url).getHost();
        return TextUtils.isEmpty(host) ? url : host;
    }

    private void openPage(String url) {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).openInBrowser(url);
        }
    }

    private void confirmDelete(DownloadEntity d) {
        // Deleting removes the saved video from the gallery too, so make the user mean it.
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_Ds_Dialog)
                .setTitle(R.string.delete_title)
                .setMessage(getString(R.string.delete_message, d.title))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, which) -> performDelete(d, true))
                .show();
    }

    /**
     * @param mayAsk false on the second attempt, so a refusal cannot loop
     * @return true when the file is gone
     */
    private boolean performDelete(DownloadEntity d, boolean mayAsk) {
        // A transfer still running is cancelled rather than deleted: there is no file yet, and
        // the engine has to be told to stop before its scratch space can be swept.
        if (!d.fromLibrary && !d.status.terminal()) {
            DownloadService.control(requireContext(), DownloadService.ACTION_CANCEL, d.id);
        }
        // Only a transfer this app ran has a notification; a library row's negative id is not
        // a notification id and clearing it would dismiss somebody else's.
        if (!d.fromLibrary) new DownloadNotifier(requireContext()).clear(d.id);

        MediaLibrary.WriteResult result = App.get().repository().delete(d);
        if (result.done) {
            // The two side files the library keeps about a video have to go with it. Left
            // behind, a collection keeps counting a file that no longer exists, and the same
            // uri handed out again by MediaStore would arrive already half-watched.
            CollectionStore.forget(requireContext(), d.outputUri);
            WatchedStore.forget(requireContext(), d.outputUri);
            return true;
        }
        if (TextUtils.isEmpty(d.outputUri)) return false;

        if (mayAsk && result.confirmation != null) {
            // From Android 11 the system's own delete request carries the delete out on approval,
            // so there is nothing to run afterwards. Before that it only granted access, and the
            // delete still has to be made.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                afterConfirmation = () -> performDelete(d, false);
            }
            if (launchConfirmation(result)) return false;
            afterConfirmation = null;
        }
        // Silent on the batch path: the caller counts what survived and says it once, where one
        // toast per file would bury the result under the noise of reporting it.
        if (mayAsk) {
            Toast.makeText(requireContext(), R.string.delete_file_failed, Toast.LENGTH_SHORT)
                    .show();
        }
        return false;
    }

    /**
     * Hands a refused write back to the system to ask about.
     *
     * <p>Android owns this decision once the file is no longer ours — a reinstall is enough to lose
     * that claim — so it asks the user directly rather than letting us act on their behalf.
     */
    private boolean launchConfirmation(MediaLibrary.WriteResult result) {
        return launchConfirmation(result.confirmation);
    }

    private boolean launchConfirmation(@Nullable IntentSender sender) {
        if (sender == null) return false;
        try {
            writeConfirmation.launch(new IntentSenderRequest.Builder(sender).build());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
}
