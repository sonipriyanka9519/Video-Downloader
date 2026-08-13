package com.ms.webview.ui.tabs;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Parcel;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.WebView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Where the open tabs live between one run of the app and the next.
 *
 * <p>Addresses, titles and previews only. The WebView's own history is left behind on purpose —
 * see {@link Tab#state} — so a restored tab loads its page fresh rather than replaying a session
 * that may be hours old.
 */
public final class TabStore {

    private static final String TAG = "Tabs";
    private static final String PREFS = "browser_tabs";
    private static final String KEY_TABS = "tabs";
    private static final String KEY_CURRENT = "current";

    /**
     * Where each tab's back history is written.
     *
     * <p>Kept as files rather than in the tab list, because a history is a {@link Bundle} of
     * arbitrary size — hundreds of kilobytes on a tab that has been used — and the list is read
     * and written on every navigation.
     */
    private static final String STATE_DIR = "tab_states";

    /**
     * Past this a history is dropped rather than written. A tab that has wandered far enough to
     * produce one this large is better reopened at its address than given a slow restore.
     */
    private static final int MAX_STATE_BYTES = 512 * 1024;

    /**
     * Where page previews are written. Cleared of orphans whenever the list is saved.
     *
     * <p>Under the app's files rather than its cache, though a picture of a web page is exactly
     * the sort of thing a cache is for. The cache is not ours to rely on: Android empties it
     * whenever it wants the space, and it does so between one run of the app and the next. Tabs
     * survived that and their pictures did not, so the switcher came back after a restart with a
     * grid of blank cards.
     */
    private static final String PREVIEW_DIR = "tab_previews";

    /** Wide enough to recognise a page by, small enough that a dozen cost nothing. */
    private static final int PREVIEW_WIDTH = 360;
    private static final int PREVIEW_QUALITY = 70;

    /**
     * How much taller than wide a card's picture is.
     *
     * <p>The top of the page, not the middle of it. A phone page is several times taller than it
     * is wide, and a card showing all of it is a grey smear; a card showing the centre of it is a
     * paragraph from halfway down. What identifies a page at a glance is its heading, and the
     * heading is at the top.
     */
    private static final float PREVIEW_RATIO = 1.1f;

    private TabStore() {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ persistence

    public static List<Tab> load(Context context) {
        List<Tab> tabs = new ArrayList<>();
        String saved = prefs(context).getString(KEY_TABS, null);
        if (TextUtils.isEmpty(saved)) return tabs;

        try {
            JSONArray array = new JSONArray(saved);
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                String id = o.optString("id");
                if (TextUtils.isEmpty(id)) id = newId();
                Tab tab = new Tab(id);
                tab.url = o.optString("url", "");
                tab.title = o.optString("title", "");
                tab.previewPath = o.optString("preview", "");
                // A preview deleted behind our back is not a reason to lose the tab.
                if (!TextUtils.isEmpty(tab.previewPath) && !new File(tab.previewPath).exists()) {
                    tab.previewPath = "";
                }
                // Its back history, so Back after a restart means the page before this one.
                loadState(context, tab);
                tabs.add(tab);
            }
        } catch (Exception e) {
            Log.w(TAG, "tab list unreadable, starting fresh", e);
            return new ArrayList<>();
        }
        return tabs;
    }

    /** The id of the tab that was in front, or null if it is no longer among them. */
    @Nullable
    public static String currentId(Context context) {
        String id = prefs(context).getString(KEY_CURRENT, null);
        return TextUtils.isEmpty(id) ? null : id;
    }

    public static void save(Context context, List<Tab> tabs, @Nullable String currentId) {
        JSONArray array = new JSONArray();
        for (Tab tab : tabs) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", tab.id);
                o.put("url", tab.url);
                o.put("title", tab.title);
                o.put("preview", tab.previewPath);
                array.put(o);
            } catch (Exception ignored) {
                // One unwritable entry is not worth losing the rest of the list over.
            }
        }
        prefs(context).edit()
                .putString(KEY_TABS, array.toString())
                .putString(KEY_CURRENT, currentId == null ? "" : currentId)
                .apply();

        for (Tab tab : tabs) saveState(context, tab);
        deleteOrphans(context, tabs);
    }

    // ---------------------------------------------------------------------- history

    /**
     * Writes a tab's back history so it survives the app closing.
     *
     * <p>Without this a restored tab began at its address with nothing behind it, and Back — the
     * one gesture that means "the page before this" — had no page before this to go to, so it
     * left for the grid instead.
     *
     * <p>A history is a {@link Bundle} the WebView built and only the WebView understands, so it
     * is stored as the bytes of that bundle rather than as anything readable. The format belongs
     * to the platform and is not promised to survive an Android upgrade, which is why every read
     * is allowed to fail: a history that cannot be understood is discarded and the tab opens at
     * its address, exactly as it did before any of this.
     */
    public static void saveState(Context context, Tab tab) {
        File file = new File(stateDir(context), tab.id + ".bin");
        if (tab.state == null) {
            file.delete();
            return;
        }

        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(tab.state);
            byte[] bytes = parcel.marshall();
            if (bytes.length > MAX_STATE_BYTES) {
                file.delete();
                return;
            }
            try (FileOutputStream out = new FileOutputStream(file)) {
                out.write(bytes);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not save history for " + tab.id + ": " + t.getMessage());
            file.delete();
        } finally {
            parcel.recycle();
        }
    }

    /** Reads a tab's back history back in, or leaves it null when there is none to be had. */
    public static void loadState(Context context, Tab tab) {
        File file = new File(stateDir(context), tab.id + ".bin");
        if (!file.exists()) return;

        Parcel parcel = Parcel.obtain();
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream in = new FileInputStream(file)) {
                int read = 0;
                while (read < bytes.length) {
                    int n = in.read(bytes, read, bytes.length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            parcel.unmarshall(bytes, 0, bytes.length);
            parcel.setDataPosition(0);
            tab.state = parcel.readBundle(TabStore.class.getClassLoader());
        } catch (Throwable t) {
            // Written by another version of the platform, or truncated. Not worth a crash: the
            // tab simply opens at its address.
            Log.w(TAG, "could not read history for " + tab.id + ": " + t.getMessage());
            tab.state = null;
            file.delete();
        } finally {
            parcel.recycle();
        }
    }

    private static void deleteState(Context context, Tab tab) {
        new File(stateDir(context), tab.id + ".bin").delete();
    }

    private static File stateDir(Context context) {
        File dir = new File(context.getFilesDir(), STATE_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // --------------------------------------------------------------------- previews

    /**
     * Draws the page as it looks right now and keeps it as the tab's card.
     *
     * <p>Drawn rather than screenshotted, so nothing outside the WebView — the sheet, the
     * keyboard, a dialog — can end up in the picture.
     *
     * @return the file written, or the previous path when there was nothing to draw.
     */
    public static String capture(Context context, WebView webView, Tab tab) {
        return capture(context, webView, tab, false);
    }

    /**
     * @param topOnly true to keep only the top of the view, for a page long enough that the rest
     *                of it says nothing.
     */
    public static String capture(Context context, View source, Tab tab, boolean topOnly) {
        if (source == null || source.getWidth() <= 0 || source.getHeight() <= 0) {
            return tab.previewPath;
        }
        Bitmap full = null;
        Bitmap cropped = null;
        Bitmap scaled = null;
        try {
            full = Bitmap.createBitmap(source.getWidth(), source.getHeight(),
                    Bitmap.Config.RGB_565);
            source.draw(new Canvas(full));

            cropped = cropToRatio(full);
            int height = Math.max(1, cropped.getHeight() * PREVIEW_WIDTH / cropped.getWidth());
            scaled = Bitmap.createScaledBitmap(cropped, PREVIEW_WIDTH, height, true);

            File file = new File(previewDir(context), tab.id + ".jpg");
            try (FileOutputStream out = new FileOutputStream(file)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, PREVIEW_QUALITY, out);
            }
            return file.getAbsolutePath();
        } catch (Throwable t) {
            // An OutOfMemory here must not take the browser with it: the card simply keeps
            // whatever picture it had, or none.
            Log.w(TAG, "preview failed for " + tab.id + ": " + t.getMessage());
            return tab.previewPath;
        } finally {
            if (scaled != null && scaled != cropped) scaled.recycle();
            if (cropped != null && cropped != full) cropped.recycle();
            if (full != null) full.recycle();
        }
    }

    /** The top of the picture, at the shape a card shows. Returns the original if it is shorter. */
    private static Bitmap cropToRatio(Bitmap source) {
        int wanted = Math.round(source.getWidth() * PREVIEW_RATIO);
        if (source.getHeight() <= wanted) return source;
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), wanted);
    }

    public static void deletePreview(Tab tab) {
        if (TextUtils.isEmpty(tab.previewPath)) return;
        try {
            new File(tab.previewPath).delete();
        } catch (Exception ignored) {
        }
        tab.previewPath = "";
    }

    /** Everything kept on disk for a tab that is going away. */
    public static void forget(Context context, Tab tab) {
        deletePreview(tab);
        deleteState(context, tab);
    }

    /**
     * Pictures and histories belonging to tabs that no longer exist — a closed tab, or a crashed
     * run. Both are keyed on the tab id, so both are swept the same way.
     */
    private static void deleteOrphans(Context context, List<Tab> tabs) {
        Set<String> previews = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (Tab tab : tabs) {
            ids.add(tab.id);
            if (!TextUtils.isEmpty(tab.previewPath)) {
                previews.add(new File(tab.previewPath).getName());
            }
        }

        File[] pictures = previewDir(context).listFiles();
        if (pictures != null) {
            for (File file : pictures) {
                if (!previews.contains(file.getName())) file.delete();
            }
        }

        File[] states = stateDir(context).listFiles();
        if (states != null) {
            for (File file : states) {
                String id = file.getName().replace(".bin", "");
                if (!ids.contains(id)) file.delete();
            }
        }
    }

    private static File previewDir(Context context) {
        File dir = new File(context.getFilesDir(), PREVIEW_DIR);
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
