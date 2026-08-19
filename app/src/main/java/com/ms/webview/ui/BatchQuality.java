package com.ms.webview.ui;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.ms.webview.R;
import com.ms.webview.detect.MediaItem;
import com.ms.webview.detect.MediaKind;
import com.ms.webview.detect.MediaVariant;
import com.ms.webview.ui.settings.DefaultQuality;

import java.util.List;

/**
 * The one quality decision that covers a whole batch — screen 03, panel C.
 *
 * <p>Named by intent rather than by number, and that is the whole idea. Four videos on a page
 * rarely share a ladder: one has 1080p, another tops out at 480p. "720p" would therefore mean
 * something for some of them and nothing for the rest, whereas "Best under 720p" resolves against
 * each video's own rungs and always means something.
 *
 * <p>Every choice resolves per video, so the batch is four different files at four appropriate
 * qualities rather than four attempts at one number.
 */
public enum BatchQuality {

    /** The default. Good enough to watch, small enough not to regret on a phone plan. */
    BEST_UNDER_720(R.string.batch_best_720),
    BEST(R.string.batch_best),
    SMALLEST(R.string.batch_smallest),
    AUDIO(R.string.batch_audio);

    @StringRes
    public final int label;

    BatchQuality(@StringRes int label) {
        this.label = label;
    }

    /**
     * The setting on screen 10 expressed as a chip, or null for "Always ask".
     *
     * <p>The mapping lives here rather than in the sheet so the two can never drift apart. What
     * the setting picks is what the sheet opens on — both the batch chip and each video's ladder
     * — because a preference nobody can see the effect of is not a preference.
     *
     * <p>Null is meaningful: "Always ask" preselects nothing, in the batch row and in the ladder
     * alike, and the download button stays inert until something is chosen. Anything else would
     * make "always ask" a lie the first time somebody tapped straight through.
     */
    @Nullable
    public static BatchQuality fromSetting(@Nullable DefaultQuality setting) {
        if (setting == null) return BEST_UNDER_720;
        switch (setting) {
            case BEST:
                return BEST;
            case UP_TO_720:
                return BEST_UNDER_720;
            case SMALLEST:
                return SMALLEST;
            case AUDIO:
                return AUDIO;
            case ASK:
            default:
                return null;
        }
    }

    /**
     * The rung this choice picks out of one video's ladder.
     *
     * @return null when the video has nothing that answers the choice — an audio-only request
     *         against a video with no sound track, most often. The caller drops it from the
     *         batch rather than substituting something the viewer did not ask for.
     */
    @Nullable
    public MediaVariant resolve(MediaItem item) {
        if (item == null || item.drmProtected) return null;
        List<MediaVariant> rungs = item.qualities();

        MediaVariant best = null;
        MediaVariant bestUnder = null;
        MediaVariant smallest = null;
        MediaVariant audio = null;

        for (MediaVariant v : rungs) {
            if (!v.kind.downloadable()) continue;

            if (v.kind == MediaKind.AUDIO) {
                if (audio == null) audio = v;
                continue;
            }
            if (best == null || v.rank() > best.rank()) best = v;
            if (smallest == null || v.rank() < smallest.rank()) smallest = v;
            if (v.qualityRung() <= 720 && (bestUnder == null || v.rank() > bestUnder.rank())) {
                bestUnder = v;
            }
        }

        switch (this) {
            case AUDIO:
                return audio;
            case BEST:
                return best;
            case SMALLEST:
                return smallest;
            case BEST_UNDER_720:
            default:
                // Falls back to the best there is. A video whose lowest rung is 1080p still has
                // to be downloadable — refusing it would be answering "not too big" with nothing.
                return bestUnder != null ? bestUnder : best;
        }
    }
}
