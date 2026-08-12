package com.ms.webview.data;

public enum DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    PUBLISHING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean active() {
        return this == QUEUED || this == RUNNING || this == PUBLISHING;
    }
}
