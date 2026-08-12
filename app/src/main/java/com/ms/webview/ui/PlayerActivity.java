package com.ms.webview.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.PopupMenu;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.ms.webview.App;
import com.ms.webview.R;

import java.io.File;
import java.util.Locale;

/** Plays a finished download inside the app. */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends AppCompatActivity {

    private static final String EXTRA_URI   = "uri";
    private static final String EXTRA_TITLE = "title";
    private static final String STATE_POSITION = "position";

    private static final int ORIENTATION_AUTO = 0;
    private static final int ORIENTATION_LANDSCAPE = 1;
    private static final int ORIENTATION_PORTRAIT = 2;

    private static final int ASPECT_FIT = 0;
    private static final int ASPECT_CROP = 1;
    private static final int ASPECT_STRETCH = 2;

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    private int orientation = ORIENTATION_AUTO;
    private int aspect = ASPECT_FIT;
    private boolean fullscreen;

    private PlayerView     playerView;
    private MaterialToolbar toolbar;
    private ExoPlayer      player;
    private Uri            uri;
    private long           resumePosition;

    // -------------------------------------------------------------------------
    // Public factory
    // -------------------------------------------------------------------------

    public static void open(Context context, String outputUri, String title) {
        Intent intent = new Intent(context, PlayerActivity.class)
                .putExtra(EXTRA_URI,   outputUri)
                .putExtra(EXTRA_TITLE, title);
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

        // ── 2. Make status bar icons dark-on-light or light-on-dark.
        //        We have a black background, so we want light (white) icons.
        applyStatusBarAppearance();

        // ── 3. Apply window-insets so the toolbar sits below the status bar
        //        and the player view sits above the navigation bar / gesture bar.
        toolbar    = findViewById(R.id.toolbar);
        playerView = findViewById(R.id.playerView);

        applyWindowInsets();

        // ── 4. Validate URI
        String raw = getIntent().getStringExtra(EXTRA_URI);
        if (TextUtils.isEmpty(raw)) {
            Toast.makeText(this, R.string.playback_failed, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
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

        // ── 5. Toolbar
        toolbar.setTitle(getIntent().getStringExtra(EXTRA_TITLE));
        toolbar.setNavigationOnClickListener(v -> finish());
        setUpMenu();

        // ── 6. Restore playback position
        if (savedInstanceState != null) {
            resumePosition = savedInstanceState.getLong(STATE_POSITION, 0);
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
     * Pads the toolbar by the status-bar height so it clears the status bar,
     * and pads the player view by the navigation-bar height so video never
     * goes under the gesture bar / nav buttons.
     */
    private void applyWindowInsets() {
        // Toolbar: add top padding = status bar height
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            // Preserve the original (XML) top padding and add status-bar inset
            view.setPadding(
                    view.getPaddingLeft(),
                    systemBars.top,           // push content below status bar
                    view.getPaddingRight(),
                    view.getPaddingBottom()
            );

            // Also grow the toolbar's height so it doesn't shrink to zero
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            // actionBarSize is already set in XML; just add status bar on top
            int actionBarSize = getActionBarSize();
            lp.height = actionBarSize + systemBars.top;
            view.setLayoutParams(lp);

            return insets; // pass insets down so children can also react
        });

        // PlayerView: add bottom padding = navigation-bar height
        ViewCompat.setOnApplyWindowInsetsListener(playerView, (view, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());

            view.setPadding(
                    view.getPaddingLeft(),
                    view.getPaddingTop(),
                    view.getPaddingRight(),
                    systemBars.bottom         // keep video above nav / gesture bar
            );

            return insets;
        });
    }

    /** Returns the resolved ?attr/actionBarSize in pixels. */
    private int getActionBarSize() {
        int[] attrs = { androidx.appcompat.R.attr.actionBarSize };
        android.content.res.TypedArray ta = obtainStyledAttributes(attrs);
        int size = ta.getDimensionPixelSize(0, 0);
        ta.recycle();
        return size;
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

    private void setUpMenu() {
        toolbar.inflateMenu(R.menu.player);
        toolbar.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.action_rotate) {
                cycleOrientation();
                return true;
            }
            if (id == R.id.action_aspect) {
                cycleAspect();
                return true;
            }
            if (id == R.id.action_fullscreen) {
                toggleFullscreen();
                return true;
            }
            if (id == R.id.action_speed) {
                showSpeedMenu();
                return true;
            }
            if (id == R.id.action_share) {
                share();
                return true;
            }
            return false;
        });
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
     * Hides the toolbar and the system bars together. The bars come back on a swipe rather than
     * staying gone, so there is always a way out even if the button is no longer on screen.
     */
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        toolbar.setVisibility(fullscreen ? View.GONE : View.VISIBLE);

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), playerView);
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars());
        else controller.show(WindowInsetsCompat.Type.systemBars());
    }

    private void showSpeedMenu() {
        View anchor = findViewById(R.id.action_speed);
        if (anchor == null) anchor = toolbar;

        PopupMenu menu = new PopupMenu(this, anchor);
        for (int i = 0; i < SPEEDS.length; i++) {
            float speed = SPEEDS[i];
            String label = speed == 1f
                    ? getString(R.string.speed_normal)
                    : String.format(Locale.US, "%.2fx", speed).replace(".00", "");
            menu.getMenu().add(0, i, i, label);
        }
        menu.setOnMenuItemClickListener(item -> {
            if (player != null) player.setPlaybackSpeed(SPEEDS[item.getItemId()]);
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

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                // A source error on a content uri almost always means the file was deleted from
                // the gallery while its row was still on screen. Naming that beats "playback
                // failed", and there is nothing to stay open for.
                boolean gone = !App.get().repository().library().exists(uri.toString());
                Toast.makeText(PlayerActivity.this,
                        getString(gone ? R.string.video_missing : R.string.playback_failed),
                        Toast.LENGTH_LONG).show();
                if (gone) {
                    App.get().repository().refreshLibrary();
                    finish();
                }
            }
        });

        player.setMediaItem(MediaItem.fromUri(uri));
        if (resumePosition > 0) player.seekTo(resumePosition);
        player.setPlayWhenReady(true);
        player.prepare();
    }

    private void releasePlayer() {
        if (player == null) return;
        resumePosition = Math.max(0, player.getCurrentPosition());
        player.release();
        player = null;
        playerView.setPlayer(null);
    }
}