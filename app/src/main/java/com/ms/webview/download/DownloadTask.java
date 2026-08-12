package com.ms.webview.download;

import com.ms.webview.data.DownloadEntity;

/** What the service needs from a transfer engine, whatever the source format is. */
public interface DownloadTask extends Runnable {

    long downloadId();

    void pause();

    void cancel();

    interface Listener {
        void onProgress(DownloadEntity entity, long bytesPerSecond);

        void onFinished(DownloadEntity entity);
    }
}
