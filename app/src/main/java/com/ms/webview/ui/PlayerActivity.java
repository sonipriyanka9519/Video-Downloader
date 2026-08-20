package com.ms.webview.ui;

import android.content.Context;
import android.content.Intent;
import android.app.PictureInPictureParams;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.ms.webview.App;
import com.ms.webview.R;
import com.ms.webview.ads.Interstitials;
import com.ms.webview.core.Formats;
import com.ms.webview.ui.downloads.WatchedStore;
import com.ms.webview.ui.player.PlayerChrome;
import com.ms.webview.ui.player.PlayerQueue;
import com.ms.webview.ui.player.QueueSheet;
import com.ms.webview.ui.player.UpNextCountdown;

import java.io.File;

/** Plays a finished download inside the app. */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity implements PlayerChrome.Host {

    private static final String TAG = "PlayerActivity";

    private static final String EXTRA_URI   = "uri";
    private static final String EXTRA_TITLE = "title";
    private static final String STATE_POSITION = "position";

    private static final int ORIENTATION_AUTO = 0;
    private static final int ORIENTATION_LANDSCAPE = 1;
    private static final int ORIENTATION_PORTRAIT = 2;

    private static final int ASPECT_FIT = 0;
    private static final int ASPECT_CROP = 1;
    private static final int ASPECT_STRETCH = 2;

    private static final int MENU_LOOP = 1;
    private static final int MENU_SPEED = 2;
    private static final int MENU_SHARE = 3;

    private static final String PREFS_PLAYER = "player";
    private static final String KEY_AUTOPLAY = "autoplay";

    /** Past this, Previous restarts the current video instead of leaving it. */
    private static final long RESTART_WINDOW_MS = 3000L;

    private int orientation = ORIENTATION_AUTO;
    private int aspect = ASPECT_FIT;
    private boolean looping;

    private PlayerView     playerView;
    /** The overlay — screen 09. Owns every control and the gesture that twins it. */
    private PlayerChrome   chrome;
    /** What plays after this one, and the pause before it does — panels C and D. */
    private PlayerQueue    queue;
    private UpNextCountdown upNext;
    @Nullable
    private QueueSheet     queueSheet;
    private ExoPlayer      player;
    private Uri            uri;
    /** The uri exactly as the library holds it — the key progress is filed under. */
    private String         libraryUri;
    private long           resumePosition;

    // -------------------------------------------------------------------------
    // Public factory
    // -------------------------------------------------------------------------

    public static void open(Context context, String outputUri, String title) {
        open(context, outputUri, title, null, null);
    }

    /**
     * The same destination as {@link #open}, handed back rather than started.
     *
     * <p>For callers that cannot start an activity themselves - a notification action needs a
     * PendingIntent, and screen 16's "Watch now" is meant to reach the player rather than drop
     * somebody on the downloads list to find the video again.
     */
    public static Intent intent(Context context, String outputUri, String title) {
        return new Intent(context, PlayerActivity.class)
                .putExtra(EXTRA_URI,   outputUri)
                .putExtra(EXTRA_TITLE, title);
    }

    /**
     * Opens a video with what the viewer was looking at when they tapped it.
     *
     * <p>The queue travels in the intent because the library's narrowing lives on the downloads
     * screen — a chip, a search box, an open collection — and none of that is knowable from in
     * here. Sending the answer cannot disagree with what was on screen.
     *
     * @param queue every video in the list, in the order it was shown; null for no queue
     * @param scope what that list was narrowed by, for the sheet's caption
     */
    public static void open(Context context, String outputUri, String title,
                            @Nullable java.util.List<PlayerQueue.Item> queue,
                            @Nullable String scope) {
        Intent intent = new Intent(context, PlayerActivity.class)
                .putExtra(EXTRA_URI,   outputUri)
                .putExtra(EXTRA_TITLE, title);
        if (queue != null) PlayerQueue.putInto(intent, queue, scope);
        context.startActivity(intent);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // ── 1. Let our layout go edge-to-edge (draws behind status bar &
        //        navigation bar) but keeps them VISIBLE.
        WindowCompat.setDecorFitsSystemWindows(PlayerActivity.this.getWindow(), false);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);
        // Asked for now so it is in hand when the video ends. Requesting it at the
        // moment of showing is requesting it after the moment has gone.
        Interstitials.preload(this);
        // The system gesture and button, routed to the same exit as the arrow.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                leave();
            }
        });

        // ── 2. Make status bar icons dark-on-light or light-on-dark.
        //        We have a black background, so we want light (white) icons.
        applyStatusBarAppearance();

        // ── 3. The video surface. The overlay is built later, once the queue is known — it
        //        asks hasQueue() while wiring its own buttons.
        playerView = findViewById(R.id.playerView);

        // What to show when the file turns out to have no picture in it. PlayerView raises this
        // by itself the moment the tracks come back without a video one, so nothing here has to
        // know in advance whether it was handed a video or a sound track — which matters, since
        // it is opened from a content uri and from share intents alike.
        //
        // Its own mechanism rather than a view laid over the top: the surface underneath is a
        // SurfaceView, and anything placed behind it is painted over.
        playerView.setDefaultArtwork(
                AppCompatResources.getDrawable(this, R.drawable.art_audio));
        playerView.setArtworkDisplayMode(PlayerView.ARTWORK_DISPLAY_MODE_FIT);

        // ── 4. Validate URI
        String raw = getIntent().getStringExtra(EXTRA_URI);
        if (TextUtils.isEmpty(raw)) {
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        libraryUri = raw;
        uri = raw.startsWith("content://")
                ? Uri.parse(raw)
                : Uri.fromFile(new File(raw));

        // Checked before the player is built rather than after it fails: opening a black screen
        // and then apologising is worse than not opening.
        if (!App.get().repository().library().exists(raw)) {
            Toast.makeText(this, R.string.video_missing, Toast.LENGTH_LONG).show();
            App.get().repository().refreshLibrary();
            finish();
            return;
        }

        // ── 5. The queue, then the overlay that reads it, then the countdown that follows it.
        queue = PlayerQueue.readFrom(getIntent(), libraryUri);
        chrome = new PlayerChrome(this, findViewById(R.id.playerRoot), this);
        upNext = new UpNextCountdown(findViewById(R.id.upNext), new UpNextCountdown.Listener() {
            @Override
            public void onCountdownFinished() {
                onNext();
            }

            @Override
            public void onCountdownCancelled() {
                // Stays on the last frame of what just finished, which is where it was. The
                // chrome comes back so there is something to press.
                chrome.show();
            }
        });

        chrome.setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        chrome.setQueueCount(queue.remaining());
        applyWindowInsets();
        applyStatusBarPolicy();

        // ── 6. Restore playback position
        if (savedInstanceState != null) {
            // A rotation, which is the same viewing continuing — it wins over anything on disk.
            resumePosition = savedInstanceState.getLong(STATE_POSITION, 0);
        } else {
            // A fresh open of something left part-way through. The store returns zero for a
            // video that was finished or barely started, so this is silent unless there is
            // genuinely somewhere to go back to.
            resumePosition = WatchedStore.resumePosition(this, libraryUri);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) initPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || player == null) initPlayer();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Filed here as well as on release, and the ordering is the point. Activities hand over as
        // A.onPause, B.onResume, A.onStop — so a position written only on the way out landed after
        // the library screen had already refreshed, and the row went on showing the old bar until
        // something else happened to rebuild it. Writing it now means the list reads the new value
        // on the very next breath.
        recordProgress();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) releasePlayer();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) releasePlayer();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        long position = player != null ? player.getCurrentPosition() : resumePosition;
        outState.putLong(STATE_POSITION, Math.max(0, position));
    }

    // -------------------------------------------------------------------------
    // Window insets
    // -------------------------------------------------------------------------

    /**
     * Keeps the chrome clear of the system bars, and lets the video fill the window.
     *
     * <p>The overlay is inset; the picture is not. A video letterboxed by the system bars as
     * well as by its own aspect ratio is a video in a box in a box, and the black it would sit
     * in is the same black the bars are drawn over anyway.
     */
    private void applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.playerRoot),
                (view, insets) -> {
                    Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                    chrome.applyInsets(bars.top, bars.bottom, bars.left, bars.right);
                    return insets;
                });
    }

    // -------------------------------------------------------------------------
    // Status-bar appearance
    // -------------------------------------------------------------------------

    /**
     * Forces light (white) status-bar icons to match our black background.
     * On API 30+ we use the new WindowInsetsController; on older APIs we fall
     * back to the deprecated View flags.
     */
    @SuppressWarnings("deprecation")
    private void applyStatusBarAppearance() {
        Window window = getWindow();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: new controller
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                // 0 = light icons (white) — correct for dark/black background
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                );
            }
        } else {
            // API 23–29: use deprecated View system-ui flags
            View decor = window.getDecorView();
            int flags  = decor.getSystemUiVisibility();
            // Remove LIGHT_STATUS_BAR flag → icons become white
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    // -------------------------------------------------------------------------
    // Controls
    // -------------------------------------------------------------------------

    // The chrome asks for these; it owns the buttons and the gestures that reach them.

    @Override
    public void onBack() {
        // The arrow and the system back go the same way - see leave(). The error exits above do
        // not: a video that would not open is not a moment to sell anything.
        leave();
    }

    @Override
    public void onRotate() {
        cycleOrientation();
    }

    @Override
    public void onAspect() {
        cycleAspect();
    }

    @Override
    public void onQueue() {
        if (queue.isEmpty()) return;

        dismissQueueSheet();
        queueSheet = new QueueSheet(this, queue, autoplay(), new QueueSheet.Listener() {
            @Override
            public void onQueueItemChosen(int position) {
                playAt(position);
            }

            @Override
            public void onAutoplayChanged(boolean enabled) {
                setAutoplay(enabled);
            }
        });
        queueSheet.show();
    }

    @Override
    public boolean hasQueue() {
        return queue != null && !queue.isEmpty();
    }

    @Override
    public void onPrevious() {
        // From part-way in, back means the start of this one — the same thing every other player
        // does, and the reason nobody loses their place by reaching for it.
        if (player != null && player.getCurrentPosition() > RESTART_WINDOW_MS) {
            player.seekTo(0);
            return;
        }
        if (queue.hasPrevious()) playAt(queue.index() - 1);
        else if (player != null) player.seekTo(0);
    }

    @Override
    public void onNext() {
        if (queue.hasNext()) playAt(queue.index() + 1);
    }

    /**
     * Switches to another video in the queue without leaving the player.
     *
     * <p>The one that is finishing has its position recorded first: moving on is still watching
     * it up to the point you moved on from, and the library should say so.
     */
    private void playAt(int position) {
        PlayerQueue.Item item = queue.at(position);
        if (item == null) return;

        upNext.stop();
        recordProgress();
        queue.moveTo(position);

        libraryUri = item.uri;
        uri = item.uri.startsWith("content://")
                ? Uri.parse(item.uri)
                : Uri.fromFile(new File(item.uri));
        resumePosition = WatchedStore.resumePosition(this, libraryUri);

        chrome.setTitle(item.title);
        chrome.setQueueCount(queue.remaining());

        if (player == null) {
            initPlayer();
            return;
        }
        player.setMediaItem(MediaItem.fromUri(uri));
        if (resumePosition > 0) player.seekTo(resumePosition);
        player.setPlayWhenReady(true);
        player.prepare();
        chrome.syncPlayIcon();
    }

    /**
     * The video ended — screen 09, panel D.
     *
     * <p>Only when autoplay is on and there is something after it. Otherwise the player stays on
     * the last frame, which is what "the end" looks like and is what somebody who turned autoplay
     * off asked for.
     */
    /** Set on the way out, so a second back press cannot start the exit twice. */
    private boolean leaving;

    private void onPlaybackEnded() {
        recordProgress();
        PlayerQueue.Item next = queue.next();
        if (autoplay() && next != null) {
            upNext.start(next);
            return;
        }

        // Nothing here. The ad for this screen is on the way out — see leave().
    }

    /**
     * The one way out of the player, and where its ad lives.
     *
     * <p>Both exits come through here: the arrow in the chrome and the system back gesture. Two
     * doors out of one room should not behave differently, and wiring only one of them is how an ad
     * ends up feeling random.
     *
     * <p>The activity finishes whether the ad shows, fails, or never loaded — showThen guarantees
     * the callback runs exactly once, so leaving can never depend on an advert.
     *
     * <p>Nothing is shown during playback, and the two-minute floor in Interstitials means opening
     * and closing three videos in a row is not three full-screen ads.
     */
    private void leave() {
        if (leaving) return;
        leaving = true;

        // Written before anything else. Stopping is what makes the position final, and onPause
        // cannot be relied on to run first once the screen starts going away.
        recordProgress();
        if (player != null) {
            player.pause();
        }

        // Queued, not shown. This screen is about to stop existing and cannot host an ad through
        // its own dismissal — see Interstitials.queueForNextScreen. The video closes now and the
        // ad appears on the screen behind it.
        Interstitials.queueForNextScreen();
        finish();
    }

    /**
     * Swaps the picture for the art tile when the file has no picture — screen 09, panel F.
     *
     * <p>PlayerView's own default artwork is dropped at the same moment: it draws its own
     * fallback the instant the tracks come back without video, and two answers to "there is no
     * picture here" stacked on top of each other is one too many.
     */
    private void applyAudioMode(boolean audio) {
        playerView.setDefaultArtwork(audio ? null
                : AppCompatResources.getDrawable(this, R.drawable.art_audio));
        chrome.setAudio(audio, getIntent().getStringExtra(EXTRA_TITLE), audioMeta());
    }

    /**
     * "Audio · 2.1 MB · 3:12", with any part it does not know left out.
     *
     * <p>The size comes from the queue, which is the only thing here that has it — the player is
     * handed a uri, and asking the file system how big it is on the main thread is not worth a
     * line of text. A video opened with no queue simply shows its length.
     */
    private String audioMeta() {
        StringBuilder meta = new StringBuilder(getString(R.string.kind_audio));

        PlayerQueue.Item item = queue == null ? null : queue.at(queue.index());
        if (item != null && item.sizeBytes > 0) {
            meta.append(" · ").append(Formats.bytes(item.sizeBytes));
        }
        long length = player == null ? 0 : player.getDuration();
        if (length > 0) meta.append(" · ").append(Formats.duration(length));
        return meta.toString();
    }

    private boolean autoplay() {
        return getSharedPreferences(PREFS_PLAYER, MODE_PRIVATE).getBoolean(KEY_AUTOPLAY, true);
    }

    private void setAutoplay(boolean enabled) {
        getSharedPreferences(PREFS_PLAYER, MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTOPLAY, enabled).apply();
    }

    /** Files how far through the current video got, without tearing the player down. */
    private void recordProgress() {
        if (player == null) return;
        WatchedStore.setProgress(this, libraryUri,
                Math.max(0, player.getCurrentPosition()), player.getDuration());
    }

    private void dismissQueueSheet() {
        if (queueSheet != null) queueSheet.dismiss();
        queueSheet = null;
    }

    /**
     * The rest — the settings somebody changes once and forgets, and sharing.
     *
     * <p>Behind a menu because none of them is reached mid-video: the design keeps the overlay
     * to the four controls with a gesture twin, and everything else here.
     */
    @Override
    public void onOverflow(View anchor) {
        androidx.appcompat.widget.PopupMenu menu = new androidx.appcompat.widget.PopupMenu(new ContextThemeWrapper(
                this, R.style.ThemeOverlay_Ds_PopupMenu), anchor);
        menu.getMenu().add(0, MENU_LOOP, 0, R.string.loop_video)
                .setCheckable(true).setChecked(looping);
        menu.getMenu().add(0, MENU_SPEED, 1, R.string.playback_speed);
        menu.getMenu().add(0, MENU_SHARE, 2, R.string.share);

        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case MENU_LOOP:
                    toggleLoop();
                    return true;
                case MENU_SPEED:
                    onSpeedList();
                    return true;
                case MENU_SHARE:
                    share();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void toggleLoop() {
        looping = !looping;
        if (player != null) {
            player.setRepeatMode(looping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        }
        toast(looping ? R.string.loop_on : R.string.loop_off);
    }

    /**
     * Picture in picture, from the button and from a swipe down.
     *
     * <p>Guarded twice: the API arrived in Android 8, and even there a device or a policy can
     * refuse it. A button that silently does nothing is worse than one that says why.
     */
    @Override
    public void onPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || !getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            toast(R.string.pip_unavailable);
            return;
        }
        try {
            enterPictureInPictureMode(new PictureInPictureParams.Builder().build());
        } catch (Exception e) {
            Log.w(TAG, "PiP refused", e);
            toast(R.string.pip_unavailable);
        }
    }

    /**
     * The chrome has no place in a thumbnail, and neither has the screen staying awake for one.
     */
    @Override
    public void onPictureInPictureModeChanged(boolean inPip, @NonNull Configuration config) {
        super.onPictureInPictureModeChanged(inPip, config);
        chrome.setChromeAllowed(!inPip);
    }

    /**
     * Cycles follow-device, locked landscape, locked portrait.
     *
     * <p>Three states rather than a simple toggle because both locks are useful: landscape for
     * watching something wide on a table, portrait for a reel where auto-rotate keeps flipping.
     */
    private void cycleOrientation() {
        orientation = (orientation + 1) % 3;
        switch (orientation) {
            case ORIENTATION_LANDSCAPE:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                toast(R.string.orientation_landscape);
                break;
            case ORIENTATION_PORTRAIT:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                toast(R.string.orientation_portrait);
                break;
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
                toast(R.string.orientation_auto);
                break;
        }
    }

    /** Fit the whole frame, crop to fill the screen, or stretch to it. */
    private void cycleAspect() {
        aspect = (aspect + 1) % 3;
        switch (aspect) {
            case ASPECT_CROP:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                toast(R.string.aspect_crop);
                break;
            case ASPECT_STRETCH:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                toast(R.string.aspect_stretch);
                break;
            default:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                toast(R.string.aspect_fit);
                break;
        }
    }

    /**
     * The status bar stays.
     *
     * <p>The design calls this screen immersive and hides the system bars with the chrome. That
     * was built and then taken out at the user's request: the clock and the battery are worth
     * more here than the last strip of black, and a video being watched is exactly when somebody
     * wants to know the time without leaving it.
     *
     * <p>The overlay still hides itself after three seconds, which is the part of immersive that
     * was actually about the video. What stays is the bar, and the chrome is padded clear of it
     * by {@link PlayerChrome#applyInsets} rather than drawn underneath.
     */
    private void applyStatusBarPolicy() {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), playerView);
        controller.show(WindowInsetsCompat.Type.systemBars());
    }

    /**
     * The full ladder, from a long-press on the speed chip.
     *
     * <p>Set through the chrome rather than on the player directly, so the chip shows what was
     * chosen — a speed the player is running at and the chip disagrees about is worse than no
     * chip at all.
     */
    @Override
    public void onSpeedList() {
        float[] speeds = chrome.speeds();
        PopupMenu menu = new PopupMenu(this, findViewById(R.id.btnPlayerSpeed));
        for (int i = 0; i < speeds.length; i++) {
            String label = speeds[i] == 1f
                    ? getString(R.string.speed_normal)
                    : getString(R.string.speed_label, Formats.speedLabel(speeds[i]));
            menu.getMenu().add(0, i, i, label);
        }
        menu.setOnMenuItemClickListener(item -> {
            chrome.setSpeed(speeds[item.getItemId()]);
            return true;
        });
        menu.show();
    }

    private void share() {
        if (uri == null) return;
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("video/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share)));
        } catch (Exception e) {
            toast(R.string.share_failed);
        }
    }

    private void toast(int message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------------------
    // Player
    // -------------------------------------------------------------------------

    private void initPlayer() {
        if (player != null || uri == null) return;

        // Decoder fallback: when the device's first-choice decoder for a stream fails or produces
        // nothing, try the next one it has — in practice the software decoder. This is why the
        // same file plays in other players and not here; they fall back and this did not.
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true);

        player = new ExoPlayer.Builder(this, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(this, forgivingExtractors()))
                .build();
        playerView.setPlayer(player);
        watchRenderers();

        player.addListener(new Player.Listener() {
            /**
             * Every state change, logged.
             *
             * <p>A video that will not play without reporting an error is invisible to
             * {@link #onPlayerError}: the player is doing what it was told and simply never
             * reaches the picture. The three ways that happens look identical on screen and are
             * fixed in different places — stuck buffering means the source is not being read,
             * ready-but-not-playing means something paused it, and ending immediately means the
             * file's timestamps say it is already over.
             */
            @Override
            public void onPlaybackStateChanged(int state) {
                Log.i(TAG, "State=" + nameOfState(state)
                        + " playWhenReady=" + player.getPlayWhenReady()
                        + " isPlaying=" + player.isPlaying()
                        + " suppression=" + player.getPlaybackSuppressionReason()
                        + " position=" + player.getCurrentPosition()
                        + " buffered=" + player.getBufferedPosition()
                        + " duration=" + player.getDuration());

                // The end of one video is where the queue takes over — screen 09, panel D.
                if (state == Player.STATE_ENDED) onPlaybackEnded();
                chrome.syncPlayIcon();
            }

            /**
             * What the file turned out to contain.
             *
             * <p>The one thing a stalled BUFFERING cannot tell you on its own: a player waits for
             * every selected track before it will play, so a file that declares a track and then
             * has nothing in it stalls exactly like a file that is still loading. Printing the
             * tracks separates those two — and says which of them is the one holding it up.
             */
            @Override
            public void onTracksChanged(Tracks tracks) {
                boolean video = false;
                for (Tracks.Group group : tracks.getGroups()) {
                    if (group.getType() == C.TRACK_TYPE_VIDEO) video = true;
                    for (int i = 0; i < group.length; i++) {
                        Format format = group.getTrackFormat(i);
                        Log.i(TAG, "Track type=" + group.getType()
                                + " mime=" + format.sampleMimeType
                                + " codecs=" + format.codecs
                                + " supported=" + group.isTrackSupported(i)
                                + " selected=" + group.isTrackSelected(i));
                    }
                }

                // Decided by what the file turned out to contain, not by its name or its type.
                // A stream served as video/mp4 with nothing but sound in it is the case that
                // caught us out in the download engine, and it would catch us out here too.
                applyAudioMode(!video && !tracks.getGroups().isEmpty());
            }

            /** Says which of the two "not playing" cases this is: paused, or held back. */
            @Override
            public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
                Log.i(TAG, "playWhenReady=" + playWhenReady + " reason=" + reason);
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // Logged in full, because "this video will not play" has several causes that look
                // identical from the outside and are fixed in completely different places: a file
                // that has been deleted, a container the extractor cannot read, and a codec this
                // particular device has no decoder for. Without the code, all three arrive here
                // as the same shrug.
                Log.w(TAG, "Playback failed: " + error.getErrorCodeName()
                        + " (" + error.errorCode + ") uri=" + uri, error);

                // A source error on a content uri almost always means the file was deleted from
                // the gallery while its row was still on screen. Naming that beats "playback
                // failed", and there is nothing to stay open for.
                boolean gone = !App.get().repository().library().exists(uri.toString());
                if (gone) {
                    Toast.makeText(PlayerActivity.this,
                            R.string.video_missing, Toast.LENGTH_LONG).show();
                    App.get().repository().refreshLibrary();
                    finish();
                    return;
                }

                // The file is there and the device cannot decode it. Saying so matters: it is not
                // a broken download, and reloading or downloading it again will not help.
                Toast.makeText(PlayerActivity.this,
                        unsupported(error) ? R.string.format_not_supported
                                : R.string.playback_failed,
                        Toast.LENGTH_LONG).show();
            }
        });

        player.setMediaItem(MediaItem.fromUri(uri));
        // Nothing else here needs the mime, but the extractor does: a content uri hides the file
        // name, and without an extension to go on the player guesses the container by sniffing.
        // That is where a saved transport stream — what an HLS download falls back to when it
        // cannot be remuxed into MP4 — most often goes wrong.
        if (resumePosition > 0) player.seekTo(resumePosition);
        player.setRepeatMode(looping ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        player.setPlayWhenReady(true);
        player.prepare();

        // The overlay drives itself from here: the play glyph, the timecodes and the seek bar
        // all follow the player rather than being told by each control that touched it.
        chrome.attach(player);
    }

    /**
     * What the decoders actually did.
     *
     * <p>The state listener can only say the player is not ready; it cannot say why. These four
     * say it: which decoder was chosen for each track, whether a single frame ever reached the
     * screen, and whether audio ran dry. A renderer holding fifty seconds of data and still
     * reporting itself unready is a decoder swallowing input and returning nothing — and the
     * missing "first frame rendered" line is what proves it.
     */
    private void watchRenderers() {
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onVideoDecoderInitialized(EventTime time, String name,
                                                  long initialised, long durationMs) {
                Log.i(TAG, "Video decoder=" + name);
            }

            @Override
            public void onAudioDecoderInitialized(EventTime time, String name,
                                                  long initialised, long durationMs) {
                Log.i(TAG, "Audio decoder=" + name);
            }

            @Override
            public void onRenderedFirstFrame(EventTime time, Object output, long renderMs) {
                Log.i(TAG, "First frame rendered after " + renderMs + "ms");
            }

            @Override
            public void onAudioUnderrun(EventTime time, int bufferSize,
                                        long bufferSizeMs, long sinceLastFeedMs) {
                Log.w(TAG, "Audio underrun: " + bufferSizeMs + "ms buffer, "
                        + sinceLastFeedMs + "ms since last feed");
            }
        });
    }

    /**
     * Reads MP4s the way every other player on the phone reads them.
     *
     * <p>An MP4 can carry an edit list: a table saying which parts of the media actually make up
     * the presentation, and when each begins. This player honours it, as the specification says to.
     * The system player, VLC and the rest ignore it — and a great many files on the web have one
     * that is wrong, written by whatever transcoder produced them. Honouring a broken edit list
     * means the presentation is empty or starts nowhere, so the file loads perfectly, reports its
     * true duration, and then sits at zero forever. Which is exactly what a viewer describes as
     * "it plays everywhere else".
     *
     * <p>So we ignore them too. The cost is small and bounded: an edit list is normally used to
     * trim a lead-in or hold audio/video in sync by a few tens of milliseconds, and ignoring a
     * correct one costs that trim. Ignoring a broken one is the difference between a video that
     * plays and one that does not.
     */
    private static DefaultExtractorsFactory forgivingExtractors() {
        return new DefaultExtractorsFactory()
                .setMp4ExtractorFlags(Mp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS)
                .setFragmentedMp4ExtractorFlags(
                        FragmentedMp4Extractor.FLAG_WORKAROUND_IGNORE_EDIT_LISTS);
    }

    private static String nameOfState(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "IDLE";
            case Player.STATE_BUFFERING:
                return "BUFFERING";
            case Player.STATE_READY:
                return "READY";
            case Player.STATE_ENDED:
                return "ENDED";
            default:
                return String.valueOf(state);
        }
    }

    /**
     * Whether the failure is the device's, not the file's.
     *
     * <p>These four mean the video is intact and this phone has no decoder for what is inside it —
     * HEVC and AV1 are the usual answers, and a transport stream saved when a remux failed is the
     * other. Nothing about downloading it again changes any of that.
     */
    private static boolean unsupported(PlaybackException error) {
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
                return true;
            default:
                return false;
        }
    }

    private void releasePlayer() {
        if (player == null) return;
        // A countdown outliving the screen it was drawn on would advance a player that is no
        // longer there.
        if (upNext != null) upNext.stop();
        dismissQueueSheet();

        resumePosition = Math.max(0, player.getCurrentPosition());
        // Recorded on the way out rather than on open, so the library reflects how much was
        // actually watched — opening a video and backing straight out is not watching it.
        // Duration is read here because it is only known once the media has been prepared.
        WatchedStore.setProgress(this, libraryUri, resumePosition, player.getDuration());

        chrome.detach();
        player.release();
        player = null;
        playerView.setPlayer(null);
    }
}