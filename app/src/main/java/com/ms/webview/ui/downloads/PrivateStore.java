package com.ms.webview.ui.downloads;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.download.MediaStorePublisher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The private folder — screen 11, panels D and E.
 *
 * <p>The invariant this exists to keep: <b>a private item is not in MediaStore.</b> Not hidden in
 * it, not flagged in it — absent. That is what makes it absent from the gallery, the widget,
 * notifications, Recent Downloads and every share target, without any of those needing to know
 * this feature exists. Anything that merely marked a row would leak the moment some other app
 * queried the store.
 *
 * <p>So moving in is a real file move: the bytes are copied into this app's own directory, which
 * no other app can read, and only then is the MediaStore entry deleted. Copy first, delete
 * second, always. The reverse order turns a failed copy into a lost video.
 *
 * <p>Nothing here runs on the main thread. Copying a video is seconds of work.
 */
public final class PrivateStore {

    private static final String TAG = "PrivateStore";

    private static final String PREFS = "private_folder";
    private static final String KEY = "items";
    private static final String DIR = "private";

    private static final String J_ID = "id";
    private static final String J_TITLE = "title";
    private static final String J_FILE = "file";
    private static final String J_MIME = "mime";
    private static final String J_SIZE = "size";
    private static final String J_DURATION = "duration";
    private static final String J_POSTER = "poster";
    private static final String J_ADDED = "added";
    private static final String J_KIND = "kind";

    /** One video in the private folder. */
    public static final class Item {
        public final String id;
        public final String title;
        /** The name it will get back if it is ever moved out. */
        public final String fileName;
        public final String mime;
        public final long sizeBytes;
        public final long durationMs;
        @Nullable
        public final String posterUrl;
        public final long addedAt;
        public final MediaKind kind;

        Item(String id, String title, String fileName, String mime, long sizeBytes,
             long durationMs, @Nullable String posterUrl, long addedAt, MediaKind kind) {
            this.id = id;
            this.title = title;
            this.fileName = fileName;
            this.mime = mime;
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
            this.posterUrl = posterUrl;
            this.addedAt = addedAt;
            this.kind = kind;
        }
    }

    private PrivateStore() {
    }

    public static int count(Context context) {
        return all(context).size();
    }

    @NonNull
    public static List<Item> all(Context context) {
        List<Item> out = new ArrayList<>();
        String raw = prefs(context).getString(KEY, null);
        if (TextUtils.isEmpty(raw)) return out;

        try {
            JSONArray items = new JSONArray(raw);
            for (int i = 0; i < items.length(); i++) {
                JSONObject one = items.optJSONObject(i);
                if (one == null) continue;

                String id = one.optString(J_ID, "");
                if (TextUtils.isEmpty(id)) continue;
                // A record whose file has gone — cleared app data, a manual wipe — is a row
                // that would open a black screen. Skipped rather than shown.
                if (!fileFor(context, id).exists()) continue;

                out.add(new Item(id,
                        one.optString(J_TITLE, ""),
                        one.optString(J_FILE, ""),
                        one.optString(J_MIME, "video/mp4"),
                        one.optLong(J_SIZE, 0),
                        one.optLong(J_DURATION, 0),
                        one.has(J_POSTER) ? one.optString(J_POSTER, null) : null,
                        one.optLong(J_ADDED, 0),
                        kindOf(one.optString(J_KIND, null))));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Private index unreadable", e);
        }
        return out;
    }

    /**
     * Copies a saved video into the private folder.
     *
     * <p>Returns the item on success and null on failure, having changed nothing. The caller
     * deletes the MediaStore entry — and only after this has returned an item, because a delete
     * before a verified copy is a lost video.
     */
    @Nullable
    public static Item moveIn(Context context, DownloadEntity d) {
        if (d == null || TextUtils.isEmpty(d.outputUri)) return null;

        String id = UUID.randomUUID().toString();
        File target = fileFor(context, id);
        //noinspection ResultOfMethodCallIgnored
        target.getParentFile().mkdirs();

        try (InputStream in = open(context, d.outputUri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) return null;

            byte[] buffer = new byte[64 * 1024];
            int read;
            long copied = 0;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                copied += read;
            }
            out.flush();

            // A copy that ran out of space part-way is a file that looks fine on disk and plays
            // for ten seconds. Checked before anything is deleted.
            long expected = d.totalBytes > 0 ? d.totalBytes : copied;
            if (copied <= 0 || copied < expected) {
                Log.w(TAG, "Short copy: " + copied + " of " + expected);
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                return null;
            }
        } catch (IOException | SecurityException e) {
            Log.w(TAG, "Could not copy into the private folder", e);
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }

        Item item = new Item(id, d.title,
                TextUtils.isEmpty(d.fileName) ? d.title : d.fileName,
                TextUtils.isEmpty(d.mime) ? "video/mp4" : d.mime,
                target.length(), d.durationMs, d.posterUrl,
                System.currentTimeMillis(), d.kind);
        record(context, item);
        return item;
    }

    /**
     * Puts a video back where everything else can see it.
     *
     * <p>The same ordering in reverse: published to MediaStore first, and the private copy only
     * removed once that has returned a uri.
     *
     * @return the new MediaStore uri, or null when it could not be published
     */
    @Nullable
    public static String moveOut(Context context, Item item) {
        File source = fileFor(context, item.id);
        if (!source.exists()) return null;

        try {
            String uri = new MediaStorePublisher(context).publish(
                    source, item.fileName, item.mime, item.kind == MediaKind.AUDIO);
            if (TextUtils.isEmpty(uri)) return null;

            forget(context, item.id);
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return uri;
        } catch (IOException e) {
            Log.w(TAG, "Could not publish out of the private folder", e);
            return null;
        }
    }

    /** Removes it for good — the file and the record together. */
    public static void delete(Context context, Item item) {
        forget(context, item.id);
        //noinspection ResultOfMethodCallIgnored
        fileFor(context, item.id).delete();
    }

    /**
     * A private item dressed as a library row.
     *
     * <p>So the private library can use the same adapter, the same rows and the same sheet as
     * the ordinary one. The path goes in {@code outputUri} because that is what every screen
     * already reads, and {@code MediaLibrary.exists} already understands a plain path.
     */
    public static DownloadEntity asEntity(Context context, Item item) {
        DownloadEntity d = new DownloadEntity();
        // The caller numbers these. Left at zero here rather than derived from the item's id,
        // because a hash can collide and two rows sharing an id means an action lands on the
        // wrong video.
        d.fromLibrary = true;
        d.status = DownloadStatus.COMPLETED;
        d.title = item.title;
        d.fileName = item.fileName;
        d.mime = item.mime;
        d.kind = item.kind;
        d.posterUrl = item.posterUrl;
        d.durationMs = item.durationMs;
        d.totalBytes = item.sizeBytes;
        d.downloadedBytes = item.sizeBytes;
        d.completedAt = item.addedAt;
        d.createdAt = item.addedAt;
        d.outputUri = fileFor(context, item.id).getAbsolutePath();
        return d;
    }

    public static File fileFor(Context context, String id) {
        return new File(new File(context.getFilesDir(), DIR), id);
    }

    // ------------------------------------------------------------------ storage

    @Nullable
    private static InputStream open(Context context, String uriString) throws IOException {
        if (!uriString.startsWith("content://")) {
            return new java.io.FileInputStream(new File(uriString));
        }
        return context.getContentResolver().openInputStream(Uri.parse(uriString));
    }

    private static void record(Context context, Item item) {
        List<Item> items = all(context);
        items.add(0, item);
        commit(context, items);
    }

    private static void forget(Context context, String id) {
        List<Item> items = all(context);
        for (int i = items.size() - 1; i >= 0; i--) {
            if (items.get(i).id.equals(id)) items.remove(i);
        }
        commit(context, items);
    }

    private static void commit(Context context, List<Item> items) {
        JSONArray all = new JSONArray();
        for (Item item : items) {
            try {
                JSONObject one = new JSONObject();
                one.put(J_ID, item.id);
                one.put(J_TITLE, item.title);
                one.put(J_FILE, item.fileName);
                one.put(J_MIME, item.mime);
                one.put(J_SIZE, item.sizeBytes);
                one.put(J_DURATION, item.durationMs);
                if (item.posterUrl != null) one.put(J_POSTER, item.posterUrl);
                one.put(J_ADDED, item.addedAt);
                one.put(J_KIND, item.kind.name());
                all.put(one);
            } catch (JSONException e) {
                Log.w(TAG, "Could not write private item " + item.id, e);
            }
        }
        prefs(context).edit().putString(KEY, all.toString()).apply();
    }

    private static MediaKind kindOf(@Nullable String name) {
        if (name != null) {
            try {
                return MediaKind.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // An older build's name. Treated as an ordinary video, which is what it is.
            }
        }
        return MediaKind.PROGRESSIVE;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
