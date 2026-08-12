package com.ms.webview;

import android.app.Application;

import com.ms.webview.data.DownloadRepository;
import com.ms.webview.detect.MediaRegistry;

/**
 * Process-wide singletons. The registry lives here (not in the Activity) so that detection
 * survives configuration changes, and so the download service can read capture headers.
 */
public class App extends Application {

    private static App instance;

    private MediaRegistry registry;
    private DownloadRepository repository;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        registry = new MediaRegistry(this);
        repository = new DownloadRepository(this);
    }

    public static App get() {
        return instance;
    }

    public MediaRegistry registry() {
        return registry;
    }

    public DownloadRepository repository() {
        return repository;
    }
}
