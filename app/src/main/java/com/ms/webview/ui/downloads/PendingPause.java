package com.ms.webview.ui.downloads;

import com.ms.webview.data.DownloadEntity;
import com.ms.webview.data.DownloadStatus;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Downloads that have been asked to stop but have not stopped yet.
 *
 * <p>Pausing is a request, not an event. The service passes it to the downloader, which notices at
 * the next point it is safe to stop — the end of a chunk, a cancelled segment fetch unwinding — and
 * only then is the row really paused. That gap is short and it is real, and the list used to spend
 * it showing the download exactly as it was: running, with a Pause button, as though the tap had
 * done nothing.
 *
 * <p>So the row says "Pausing…" instead. It is the honest description of the state, it explains why
 * the bar may still creep for a moment, and it stops somebody pressing Pause a second time because
 * the first one looked ignored.
 *
 * <p>In memory only, and deliberately. This is about the seconds after a tap; a request that does
 * not survive the process dying is a request whose download is not running any more either.
 */
public final class PendingPause {

    private static final Set<Long> pending = ConcurrentHashMap.newKeySet();

    private PendingPause() {
    }

    /** Called the moment the viewer taps Pause, before the service has been told. */
    public static void requested(long downloadId) {
        pending.add(downloadId);
    }

    public static void forget(long downloadId) {
        pending.remove(downloadId);
    }

    /**
     * Whether this row is still on its way to a stop.
     *
     * <p>Self-clearing: anything that is no longer running has arrived wherever it was going, so
     * asking the question is also what tidies up after it. That covers the download that paused,
     * the one that finished the last chunk and completed anyway, and the one that failed on the
     * way — none of which sends anything back here to say so.
     */
    public static boolean isPausing(DownloadEntity d) {
        if (d == null || !pending.contains(d.id)) return false;
        if (d.status == DownloadStatus.RUNNING) return true;
        pending.remove(d.id);
        return false;
    }
}
