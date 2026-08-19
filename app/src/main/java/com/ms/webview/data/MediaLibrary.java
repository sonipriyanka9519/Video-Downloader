package com.ms.webview.data;

import android.app.RecoverableSecurityException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentSender;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;

import com.ms.webview.core.Formats;
import com.ms.webview.detect.MediaKind;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The finished-downloads list, read from the device rather than from anything this app stores.
 *
 * <p>This is the whole point of dropping the database: the videos are the record. Clear the
 * app's data, uninstall it, reinstall it — the files are still in {@code Movies/Webview} and
 * this reads them straight back. Nothing to migrate, nothing to lose, and no way for the list
 * to disagree with the gallery about what exists.
 *
 * <p>The rows it produces wear {@link DownloadEntity} because that is what the list already
 * speaks; a completed download and a video on disk really are the same thing here.
 */
public class MediaLibrary {

    private static final String TAG = "MediaLibrary";
    public static final String ALBUM = "Webview";

    private final Context context;

    public MediaLibrary(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Where published videos live, as MediaStore spells it. */
    public static String relativePath() {
        return Environment.DIRECTORY_MOVIES + File.separator + ALBUM + File.separator;
    }

    public static Uri collection() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
    }

    /**
     * The other half of the library: sound tracks downloaded on their own.
     *
     * <p>A separate collection because that is where they were published, and they were published
     * there because MediaStore sorts the device's media by it — filing an audio file under video
     * puts it in the gallery as something that opens to a black screen and will not play.
     */
    public static Uri audioCollection() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
    }

    /** Where published sound tracks live, as MediaStore spells it. */
    public static String audioRelativePath() {
        return Environment.DIRECTORY_MUSIC + File.separator + ALBUM + File.separator;
    }

    /**
     * Everything this app has saved, newest first.
     *
     * <p>Scoped to our own album rather than the whole video collection: the downloads list is a
     * list of downloads, not of every clip on the phone.
     */
    public List<DownloadEntity> saved() {
        List<DownloadEntity> out = new ArrayList<>();
        scan(out, collection(), relativePath(), Environment.DIRECTORY_MOVIES, false);
        scan(out, audioCollection(), audioRelativePath(), Environment.DIRECTORY_MUSIC, true);
        // Each scan is sorted, the pair is not. One list, newest first, whichever it came from.
        Collections.sort(out, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    /**
     * Reads one collection's worth of this app's own album into {@code out}.
     *
     * <p>Written once and run twice. Every column named here is a {@link MediaStore.MediaColumns}
     * — the same string on the video table and the audio one — so the only things that differ
     * between the two passes are the collection, the folder, and whether a row is expected to
     * have a picture.
     */
    private void scan(List<DownloadEntity> out, Uri collection, String path, String directory,
                      boolean audio) {
        String[] columns = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
        };

        // Exactly this app's own folder, never the device's video collection at large. The
        // media permission grants sight of everything; the folder is what keeps the list to
        // what this app put there. Two conditions rather than one, because a path match alone
        // would take in any sub-folder and a folder-name match alone would take in a "Webview"
        // directory sitting anywhere else on the device.
        String selection;
        String[] args;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Written with a trailing separator, but not every OEM stores it back that way.
            selection = MediaStore.MediaColumns.RELATIVE_PATH + " IN (?, ?) AND "
                    + MediaStore.MediaColumns.BUCKET_DISPLAY_NAME + " = ?";
            args = new String[]{path, path.substring(0, path.length() - 1), ALBUM};
        } else {
            // No RELATIVE_PATH before Q, so match on the path column the publisher wrote to.
            selection = MediaStore.MediaColumns.DATA + " LIKE ? AND "
                    + MediaStore.MediaColumns.BUCKET_DISPLAY_NAME + " = ?";
            args = new String[]{"%/" + directory + "/" + ALBUM + "/%", ALBUM};
        }

        try (Cursor c = context.getContentResolver().query(
                collection, columns, selection, args,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")) {

            if (c == null) return;

            int idAt = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameAt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int sizeAt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int durationAt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION);
            int mimeAt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE);
            int dateAt = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED);
            // Not OrThrow: the audio table carries no picture, and some OEMs omit the columns
            // rather than returning them empty. A missing index is answered with no quality
            // label, which is the right answer for a sound track anyway.
            int widthAt = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int heightAt = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);

            while (c.moveToNext()) {
                out.add(rowOf(c, collection, audio, idAt, nameAt, sizeAt, durationAt,
                        widthAt, heightAt, mimeAt, dateAt));
            }
        } catch (SecurityException e) {
            // No media permission yet. An empty list is the honest answer until it is granted.
            Log.i(TAG, "Cannot read the library without permission");
        } catch (Exception e) {
            Log.w(TAG, "Library scan failed: " + e.getMessage());
        }
    }

    private DownloadEntity rowOf(Cursor c, Uri collection, boolean audio,
                                 int idAt, int nameAt, int sizeAt, int durationAt,
                                 int widthAt, int heightAt, int mimeAt, int dateAt) {
        long mediaId = c.getLong(idAt);
        // The collection this row came from, not the video one. An audio id resolved against
        // the video table is a uri that points at nothing, or worse at some other file.
        Uri uri = Uri.withAppendedPath(collection, String.valueOf(mediaId));

        DownloadEntity d = new DownloadEntity();
        // Negative, so a video read off the device can never be mistaken for a transfer this
        // app is running — the two live in different places and are deleted differently.
        d.id = -mediaId;
        d.fromLibrary = true;
        d.status = DownloadStatus.COMPLETED;
        d.kind = audio ? MediaKind.AUDIO : MediaKind.PROGRESSIVE;

        d.title = stripExtension(c.getString(nameAt));
        d.fileName = c.getString(nameAt);
        d.mime = c.getString(mimeAt);
        d.outputUri = uri.toString();
        // Glide decodes a frame straight from the content uri, so the poster survives a
        // reinstall along with the video instead of living in a cache we no longer have.
        d.posterUrl = uri.toString();

        d.totalBytes = c.getLong(sizeAt);
        d.downloadedBytes = d.totalBytes;
        d.durationMs = c.getLong(durationAt);

        if (widthAt >= 0 && heightAt >= 0) {
            int width = c.getInt(widthAt);
            int height = c.getInt(heightAt);
            if (width > 0 && height > 0) d.quality = Formats.quality(width, height);
        }

        long addedSeconds = c.getLong(dateAt);
        d.createdAt = addedSeconds * 1000L;
        d.completedAt = d.createdAt;
        return d;
    }

    /** True when the uri still resolves to a file. A gallery delete makes this false. */
    public boolean exists(@Nullable String uriString) {
        if (TextUtils.isEmpty(uriString)) return false;
        if (!uriString.startsWith("content://")) return new File(uriString).exists();
        try {
            ContentResolver resolver = context.getContentResolver();
            try (Cursor c = resolver.query(Uri.parse(uriString),
                    new String[]{MediaStore.MediaColumns._ID}, null, null, null)) {
                return c != null && c.moveToFirst();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** What a change to a stored file came to — a delete, or a rename. */
    public static class WriteResult {
        public final boolean done;
        /** Set when the system needs the user to confirm, which is the case for files we no
         *  longer own — anything saved by a previous install of the app. */
        @Nullable
        public final IntentSender confirmation;

        WriteResult(boolean done, @Nullable IntentSender confirmation) {
            this.done = done;
            this.confirmation = confirmation;
        }
    }

    /**
     * What a delete of several files at once came to.
     *
     * <p>Split three ways because the three outcomes need different answers: what is gone needs
     * forgetting, what the system will ask about is not gone yet but might be in a moment, and
     * what failed outright is worth saying out loud.
     */
    public static class BatchResult {
        public final List<String> deleted = new ArrayList<>();
        /** Covered by {@link #confirmation} — gone once the viewer agrees, still there if not. */
        public final List<String> pending = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
        /** One request for every pending file, so the viewer is asked once and not per file. */
        @Nullable
        public IntentSender confirmation;
    }

    /**
     * Deletes many files, asking the viewer at most once.
     *
     * <p>The reason this is not a loop over {@link #delete} in the caller: a file the app no
     * longer owns — anything saved by a previous install — needs the system's consent, and
     * asking per file means a stack of identical dialogs that nobody can get to the end of.
     * {@code createDeleteRequest} takes a list, so one dialog names every file at once and the
     * system carries the whole delete out on approval.
     *
     * <p>Files that delete silently are deleted here and now; only the refused ones go into the
     * request. Making the viewer confirm files that needed no confirming would be asking a
     * question that has already been answered.
     *
     * <p>Before Android 11 there is no batch form — consent there comes out of one
     * {@link SecurityException} at a time — so those are reported as failures for the caller to
     * offer individually rather than silently dropped.
     */
    public BatchResult deleteAll(@Nullable List<String> uriStrings) {
        BatchResult result = new BatchResult();
        if (uriStrings == null || uriStrings.isEmpty()) return result;

        List<Uri> refused = new ArrayList<>();
        for (String uriString : uriStrings) {
            if (TextUtils.isEmpty(uriString)) continue;

            if (!uriString.startsWith("content://")) {
                if (new File(uriString).delete()) result.deleted.add(uriString);
                else result.failed.add(uriString);
                continue;
            }

            Uri uri = Uri.parse(uriString);
            try {
                if (context.getContentResolver().delete(uri, null, null) > 0) {
                    result.deleted.add(uriString);
                } else {
                    result.failed.add(uriString);
                }
            } catch (SecurityException notOurs) {
                refused.add(uri);
                result.pending.add(uriString);
            } catch (Exception e) {
                Log.w(TAG, "Delete failed: " + e.getMessage());
                result.failed.add(uriString);
            }
        }

        if (refused.isEmpty()) return result;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                result.confirmation = MediaStore.createDeleteRequest(
                        context.getContentResolver(), refused).getIntentSender();
                return result;
            } catch (Exception e) {
                Log.w(TAG, "No batch delete confirmation available: " + e.getMessage());
            }
        }
        // No way to ask for the group: hand them back as failures rather than leaving the
        // caller to believe they went.
        result.failed.addAll(result.pending);
        result.pending.clear();
        return result;
    }

    public WriteResult delete(@Nullable String uriString) {
        if (TextUtils.isEmpty(uriString)) return new WriteResult(false, null);

        if (!uriString.startsWith("content://")) {
            return new WriteResult(new File(uriString).delete(), null);
        }

        Uri uri = Uri.parse(uriString);
        try {
            int rows = context.getContentResolver().delete(uri, null, null);
            return new WriteResult(rows > 0, null);
        } catch (SecurityException e) {
            return new WriteResult(false, deleteConfirmationFor(uri, e));
        } catch (Exception e) {
            Log.w(TAG, "Delete failed: " + e.getMessage());
            return new WriteResult(false, null);
        }
    }

    /**
     * Gives a stored video a new file name.
     *
     * <p>The extension is kept whatever is typed. A name is what a person recognises the video by;
     * the extension is what the system opens it with, and letting the first overwrite the second
     * turns a rename into a file nothing will play.
     *
     * <p>Fails the same way a delete does, and for the same reason — a file saved by a previous
     * install is no longer ours to write — so it comes back with the same confirmation to raise.
     */
    public WriteResult rename(@Nullable String uriString, @Nullable String newName,
                              @Nullable String currentName) {
        if (TextUtils.isEmpty(uriString) || TextUtils.isEmpty(newName)) {
            return new WriteResult(false, null);
        }

        String extension = extensionOf(currentName);
        String bare = stripExtension(newName.trim());
        if (TextUtils.isEmpty(bare)) return new WriteResult(false, null);
        String fileName = bare + extension;

        if (!uriString.startsWith("content://")) {
            File from = new File(uriString);
            File to = new File(from.getParentFile(), fileName);
            return new WriteResult(from.renameTo(to), null);
        }

        Uri uri = Uri.parse(uriString);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        try {
            int rows = context.getContentResolver().update(uri, values, null, null);
            return new WriteResult(rows > 0, null);
        } catch (SecurityException e) {
            return new WriteResult(false, writeConfirmationFor(uri, e));
        } catch (Exception e) {
            Log.w(TAG, "Rename failed: " + e.getMessage());
            return new WriteResult(false, null);
        }
    }

    /** The dot and what follows it, or nothing where there is no extension to keep. */
    private static String extensionOf(@Nullable String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        return dot <= 0 ? "" : fileName.substring(dot);
    }

    /**
     * A reinstall makes the app a stranger to its own files: MediaStore attributes them to the
     * previous install and refuses a silent delete. Both API levels have a way to ask the user
     * instead, which is better than telling them the file cannot be removed.
     *
     * <p>From Android 11 this asks for the delete itself, and the system carries it out on
     * approval — there is nothing left for the caller to retry.
     */
    @Nullable
    private IntentSender deleteConfirmationFor(Uri uri, SecurityException cause) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return MediaStore.createDeleteRequest(
                        context.getContentResolver(), Collections.singletonList(uri))
                        .getIntentSender();
            }
            return recoverableActionOf(cause);
        } catch (Exception e) {
            Log.w(TAG, "No delete confirmation available: " + e.getMessage());
        }
        return null;
    }

    /**
     * The same idea for a change that is not a delete.
     *
     * <p>A separate request, and it has to be. From Android 11 a delete request and a write
     * request are different things: the first asks "may this be removed" and removes it on yes.
     * Asking that in order to rename something would put the wrong question to the viewer and
     * destroy the file when they agreed to it.
     *
     * <p>A write request only grants access — the change itself has to be made again afterwards,
     * which is what the caller's retry is for.
     */
    @Nullable
    private IntentSender writeConfirmationFor(Uri uri, SecurityException cause) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return MediaStore.createWriteRequest(
                        context.getContentResolver(), Collections.singletonList(uri))
                        .getIntentSender();
            }
            return recoverableActionOf(cause);
        } catch (Exception e) {
            Log.w(TAG, "No write confirmation available: " + e.getMessage());
        }
        return null;
    }

    /**
     * Android 10's one answer to both: the exception itself carries the request to show, and it
     * grants access rather than doing anything, so either caller has to try again afterwards.
     */
    @Nullable
    private static IntentSender recoverableActionOf(SecurityException cause) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && cause instanceof RecoverableSecurityException) {
            return ((RecoverableSecurityException) cause)
                    .getUserAction().getActionIntent().getIntentSender();
        }
        return null;
    }

    private static String stripExtension(@Nullable String name) {
        if (TextUtils.isEmpty(name)) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
