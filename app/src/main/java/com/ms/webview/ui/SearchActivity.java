package com.ms.webview.ui;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.URLUtil;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.ms.webview.R;
import com.ms.webview.core.Formats;
import com.ms.webview.ui.home.SearchHistory;
import com.ms.webview.ui.home.Shortcuts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where an address is typed.
 *
 * <p>A screen of its own rather than a panel dropped under the browser's address bar. The bar in
 * the browser now only ever displays where you are — it cannot be typed into — and this is what
 * opens when it is pressed. Two things follow from the separation, and both were problems before
 * it: what is being typed can never be mistaken for where the browser is, and leaving without
 * choosing anything cannot change the page, because the browser is not told about anything until
 * this screen returns a result.
 */
public class SearchActivity extends AppCompatActivity
        implements SearchQueryAdapter.Listener, SearchHistoryAdapter.Listener {

    /** The address the browser is showing, so this screen can offer it back. */
    public static final String EXTRA_CURRENT_URL = "current_url";
    public static final String EXTRA_CURRENT_TITLE = "current_title";

    /** What the viewer chose: an address to open, or something to search for. */
    public static final String EXTRA_QUERY = "query";

    private EditText input;
    private ImageButton clear;

    private View pageCard;
    private ImageView pageIcon;
    private TextView pageTitleView;
    private TextView pageUrlView;

    private View clipCard;
    private ImageView clipIcon;
    private TextView clipLabel;
    private TextView clipValue;
    private ImageButton clipReveal;

    private TextView historyLabel;
    private RecyclerView suggestions;
    /** Past searches, offered while the box is empty. */
    private SearchQueryAdapter queryAdapter;
    /** Pages visited, offered once there is something to match them against. */
    private SearchHistoryAdapter historyAdapter;

    private String currentUrl = "";
    private String currentTitle = "";

    /** The link on the clipboard, or null when it holds something that is not one. */
    @Nullable
    private String clipboardLink;
    /** Everything the clipboard holds, link or plain text. */
    @Nullable
    private String clipboardText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        applySystemBarInsets();

        currentUrl = valueOf(getIntent().getStringExtra(EXTRA_CURRENT_URL));
        currentTitle = valueOf(getIntent().getStringExtra(EXTRA_CURRENT_TITLE));

        bindViews();
        bindCurrentPage();
        showSuggestionsFor("");

        // Opened to be typed into, so the keyboard comes with it. The address already in the bar
        // is offered selected: one keystroke replaces it, and a tap puts the cursor in it.
        input.setText(currentUrl);
        input.selectAll();
        input.requestFocus();
        input.postDelayed(this::showKeyboard, 120);
    }

    /**
     * Keeps the search field out from under the status bar.
     *
     * <p>The app draws behind the system bars, which is what lets a page run to the top of the
     * screen. A screen that is all chrome gains nothing from it: without this the search field
     * started at the top of the display rather than the top of the usable area, and the clock and
     * battery icons sat on top of it.
     *
     * <p>The bottom inset is taken as well, so the last suggestion clears the gesture bar. The
     * keyboard is not included — the window is resized for it, so counting it here would pad the
     * list twice.
     */
    private void applySystemBarInsets() {
        View root = findViewById(R.id.searchRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private void bindViews() {
        input = findViewById(R.id.searchInput);
        clear = findViewById(R.id.btnSearchClear);

        pageCard = findViewById(R.id.pageCard);
        pageIcon = findViewById(R.id.pageIcon);
        pageTitleView = findViewById(R.id.pageTitle);
        pageUrlView = findViewById(R.id.pageUrl);

        clipCard = findViewById(R.id.clipCard);
        clipIcon = findViewById(R.id.clipIcon);
        clipLabel = findViewById(R.id.clipLabel);
        clipValue = findViewById(R.id.clipUrl);
        clipReveal = findViewById(R.id.clipReveal);

        historyLabel = findViewById(R.id.historyLabel);

        suggestions = findViewById(R.id.searchSuggestions);
        suggestions.setLayoutManager(new LinearLayoutManager(this));
        queryAdapter = new SearchQueryAdapter(this);
        historyAdapter = new SearchHistoryAdapter(this, this);

        findViewById(R.id.btnSearchBack).setOnClickListener(v -> finish());
        clear.setOnClickListener(v -> input.setText(""));

        input.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (!go) return false;
            submit(input.getText().toString());
            return true;
        });

        // Every keystroke narrows the list, so the answer moves towards the finger rather than
        // the finger having to hunt for it.
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int st, int c, int a) {
            }

            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                clear.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
                showSuggestionsFor(s.toString());
            }
        });
    }

    // ------------------------------------------------------------- the current page

    private void bindCurrentPage() {
        if (TextUtils.isEmpty(currentUrl)) {
            pageCard.setVisibility(View.GONE);
            return;
        }

        pageTitleView.setText(TextUtils.isEmpty(currentTitle)
                ? Formats.hostOf(currentUrl) : currentTitle);
        pageUrlView.setText(currentUrl);

        int brand = Shortcuts.iconForUrl(currentUrl);
        pageIcon.setImageResource(brand != 0 ? brand : R.drawable.ic_globe);
        pageCard.setVisibility(View.VISIBLE);

        // Tapping the page you are already on means "never mind" — so it closes without asking
        // the browser to do anything.
        findViewById(R.id.pageOpen).setOnClickListener(v -> finish());
        findViewById(R.id.pageShare).setOnClickListener(v -> share(currentUrl));
        findViewById(R.id.pageCopy).setOnClickListener(v -> copy(currentUrl));
        // Edit puts it in the box rather than opening it: the point of editing is to change it.
        findViewById(R.id.pageEdit).setOnClickListener(v -> {
            input.setText(currentUrl);
            input.setSelection(currentUrl.length());
        });
    }

    // ---------------------------------------------------------------- the clipboard

    /**
     * Reads the clipboard the first moment it is legal to.
     *
     * <p>From Android 10 an app may only read the clipboard while its window has focus. In
     * {@code onCreate} it does not: the window has not been attached, the read comes back empty,
     * and the card is hidden for a clipboard that plainly has a link on it. This is the earliest
     * point at which the system will answer.
     *
     * <p>Read again on every return of focus, so a link copied from another app while this screen
     * was open is picked up when the viewer comes back to it. Doing nothing when the clipboard
     * has not changed keeps that from collapsing a card the viewer had already revealed.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;

        String latest = clipboardText();
        if (latest != null && latest.equals(clipboardText)) return;
        bindClipboard();
    }

    private void bindClipboard() {
        clipboardText = clipboardText();
        clipboardLink = clipboardText != null && URLUtil.isNetworkUrl(clipboardText)
                ? clipboardText : null;

        // Offered even when it is the address already open. Hiding it there was a guess about
        // what the viewer meant by copying it, and the wrong one: having the same link on the
        // clipboard and on screen is the ordinary result of having just shared the page, and the
        // two cards say different things — one is where you are, the other is what you are
        // holding. Both are worth being able to act on.
        if (TextUtils.isEmpty(clipboardText)) {
            clipCard.setVisibility(View.GONE);
            return;
        }

        clipLabel.setText(clipboardLink != null
                ? R.string.link_you_copied : R.string.text_you_copied);
        clipValue.setText(clipboardText);

        int brand = clipboardLink == null ? 0 : Shortcuts.iconForUrl(clipboardLink);
        clipIcon.setImageResource(brand != 0 ? brand : R.drawable.ic_globe);

        revealClipboard(false);
        clipReveal.setOnClickListener(v ->
                revealClipboard(clipValue.getVisibility() != View.VISIBLE));
        findViewById(R.id.clipRow).setOnClickListener(v -> submit(clipboardText));

        clipCard.setVisibility(View.VISIBLE);
    }

    /** Shows or hides what was copied, and turns the eye over to match. */
    private void revealClipboard(boolean visible) {
        clipValue.setVisibility(visible ? View.VISIBLE : View.GONE);
        clipReveal.setImageResource(visible ? R.drawable.ic_eye_off : R.drawable.ic_eye);
    }

    @Nullable
    private String clipboardText() {
        try {
            ClipboardManager clipboard = ContextCompat.getSystemService(
                    this, ClipboardManager.class);
            if (clipboard == null || !clipboard.hasPrimaryClip()) return null;

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return null;

            CharSequence text = clip.getItemAt(0).coerceToText(this);
            if (text == null) return null;

            String trimmed = text.toString().trim();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Exception e) {
            // A clipboard the system will not hand over is one with no suggestion in it.
            return null;
        }
    }

    // --------------------------------------------------------------- past searches

    /**
     * What to offer under the box, which is a different question before and after a key is
     * pressed.
     *
     * <p>Before: the searches made recently. There is nothing to match against yet, and a list of
     * what was last looked for is the most likely answer to what is being looked for now.
     *
     * <p>After: every page ever visited, matched against what has been typed. This is the list
     * worth searching — it is far longer, it holds the page titles, and a half-typed word is far
     * more likely to be somewhere in one of those than in the handful of searches. Offering only
     * past searches meant typing a word that was plainly in the history returned nothing.
     *
     * <p>Matched on containing rather than starting with, in both cases: half-remembered things
     * are usually remembered from the middle — "download" finds "facebook download apk".
     */
    private void showSuggestionsFor(String typed) {
        String needle = typed.trim().toLowerCase(Locale.US);

        if (needle.isEmpty()) {
            List<String> queries = SearchHistory.queries(this);
            suggestions.setAdapter(queryAdapter);
            queryAdapter.submit(queries);
            historyLabel.setText(R.string.recent);
            historyLabel.setVisibility(queries.isEmpty() ? View.GONE : View.VISIBLE);
            return;
        }

        List<SearchHistory.Entry> matching = new ArrayList<>();
        for (SearchHistory.Entry entry : SearchHistory.all(this)) {
            // The name and the address both, because a page is remembered by either — its title
            // when it had one, and its host when it did not.
            if (contains(entry.title, needle) || contains(entry.url, needle)) matching.add(entry);
        }

        suggestions.setAdapter(historyAdapter);
        historyAdapter.submit(matching);
        historyLabel.setText(R.string.from_history);
        historyLabel.setVisibility(matching.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static boolean contains(@Nullable String haystack, String lowercaseNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.US).contains(lowercaseNeedle);
    }

    @Override
    public void onRunQuery(String query) {
        submit(query);
    }

    /** The arrow beside a suggestion: put it in the box to be narrowed rather than run it. */
    @Override
    public void onEditQuery(String query) {
        input.setText(query);
        input.setSelection(query.length());
    }

    /** A page from the history: go straight to it, since it is an address and not a guess. */
    @Override
    public void onOpenHistory(SearchHistory.Entry entry) {
        submit(entry.url);
    }

    /** Forgetting one does not close the screen: tidying is done several rows at a time. */
    @Override
    public void onRemoveHistory(SearchHistory.Entry entry) {
        SearchHistory.remove(this, entry.url);
        showSuggestionsFor(input.getText().toString());
    }

    // ------------------------------------------------------------------- finishing

    /**
     * Hands the choice back to the browser and closes.
     *
     * <p>The only way this screen changes anything. Leaving any other way returns nothing, so the
     * browser keeps the page it had and its address bar keeps reading what it read before — which
     * is what going in and coming back out unchanged should do.
     */
    private void submit(@Nullable String query) {
        if (TextUtils.isEmpty(query) || query.trim().isEmpty()) return;

        hideKeyboard();
        Intent result = new Intent();
        result.putExtra(EXTRA_QUERY, query.trim());
        setResult(RESULT_OK, result);
        finish();
    }

    private void copy(String url) {
        ClipboardManager clipboard = ContextCompat.getSystemService(this, ClipboardManager.class);
        if (clipboard == null || TextUtils.isEmpty(url)) return;
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), url));
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show();
    }

    private void share(String url) {
        if (TextUtils.isEmpty(url)) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_link_via)));
        } catch (ActivityNotFoundException e) {
            // No chooser on the device, which is not something to crash over.
        }
    }

    private void showKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(this, InputMethodManager.class);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = ContextCompat.getSystemService(this, InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private static String valueOf(@Nullable String value) {
        return value == null ? "" : value;
    }
}
