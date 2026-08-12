package com.ms.webview.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.ms.webview.data.MediaLibrary;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Moves a finished download out of the app's private area and into the user's video library.
 *
 * <p>Downloading straight into MediaStore would be simpler, but partial files there are visible
 * to the gallery and survive awkwardly if the transfer dies. Private scratch space first, then
 * one publish step, keeps the library clean and needs no storage permission on Q and above.
 */
public class MediaStorePublisher {

    /** Shared with the reader, so the folder written to is the folder scanned back. */
    private static final String ALBUM = MediaLibrary.ALBUM;

    private final Context context;

    public MediaStorePublisher(Context context) {
        this.context = context.getApplicationContext();
    }

    /** @return the published location, as a content uri string or an absolute path. */
    public String publish(File source, String displayName, String mime) throws IOException {
        if (mime == null || mime.isEmpty()) mime = "video/mp4";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishToMediaStore(source, displayName, mime);
        }
        return publishLegacy(source, displayName);
    }

    private String publishToMediaStore(File source, String displayName, String mime) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, mime);
        values.put(MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + File.separator + ALBUM);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri item = resolver.insert(collection, values);
        if (item == null) throw new IOException("MediaStore rejected the insert");

        try (InputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(item)) {
            if (out == null) throw new IOException("Cannot open MediaStore output");
            copy(in, out);
        } catch (IOException e) {
            resolver.delete(item, null, null);
            throw e;
        }

        values.clear();
        values.put(MediaStore.Video.Media.IS_PENDING, 0);
        resolver.update(item, values, null, null);

        //noinspection ResultOfMethodCallIgnored
        source.delete();
        return item.toString();
    }

    private String publishLegacy(File source, String displayName) throws IOException {
        File dir = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM);
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);

        File target = new File(dir, displayName);
        if (!source.renameTo(target)) {
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(target)) {
                copy(in, out);
            }
            //noinspection ResultOfMethodCallIgnored
            source.delete();
        }
        return scanForUri(target);
    }

    /**
     * Scans the new file into the media database and waits briefly for the resulting content
     * uri. Handing the UI a content uri rather than a path keeps "open" working on API 24-28,
     * where a file:// uri in an Intent throws FileUriExposedException.
     */
    private String scanForUri(File target) {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Uri> scanned = new AtomicReference<>();
        MediaScannerConnection.scanFile(
                context, new String[]{target.getAbsolutePath()}, null,
                (path, uri) -> {
                    scanned.set(uri);
                    latch.countDown();
                });
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Uri uri = scanned.get();
        return uri != null ? uri.toString() : target.getAbsolutePath();
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }
}