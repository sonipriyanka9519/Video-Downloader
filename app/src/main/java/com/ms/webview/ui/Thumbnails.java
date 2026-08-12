package com.ms.webview.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.Base64;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.LazyHeaders;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.signature.ObjectKey;
import com.ms.webview.R;
import com.ms.webview.detect.MediaItem;

import java.io.File;
import java.util.Map;

/**
 * Thumbnail loading for two very different sources: poster URLs from the platform, and the
 * base64 frames captured live from the playing video.
 */
public final class Thumbnails {

    private Thumbnails() {
    }

    public static void load(ImageView view, MediaItem item) {
        load(view, item, null);
    }

    /**
     * @param placeholder shown while the image is being fetched and hidden once it settles,
     *                    either way. Left visible when there is nothing to load yet, because
     *                    that is a preview still on its way rather than one that failed.
     */
    public static void load(ImageView view, MediaItem item, @Nullable View placeholder) {
        if (item == null) {
            clear(view);
            view.setImageResource(R.drawable.ic_video);
            show(placeholder);
            return;
        }
        load(view, item.thumbnail(), item.posterHeaders, placeholder);
    }

    public static void load(ImageView view, String source, Map<String, String> headers) {
        load(view, source, headers, null);
    }

    public static void load(ImageView view, String source, Map<String, String> headers,
                            @Nullable View placeholder) {
        if (TextUtils.isEmpty(source)) {
            clear(view);
            view.setImageResource(R.drawable.ic_video);
            show(placeholder);
            return;
        }

        if (source.startsWith("data:")) {
            // Captured frames change every couple of seconds, so routing them through Glide
            // would fill its cache with single-use keys. Decode straight to a bitmap instead.
            Bitmap bitmap = decode(source);
            clear(view);
            if (bitmap != null) view.setImageBitmap(bitmap);
            else view.setImageResource(R.drawable.ic_video);
            hide(placeholder);
            return;
        }

        if (source.startsWith("/")) {
            // A frame we decoded out of the video. A download's poster is rewritten at the same
            // path once the file finishes, and Glide keys File loads by path alone — without a
            // signature it would keep serving the placeholder-era image forever.
            File file = new File(source);
            Glide.with(view)
                    .load(file)
                    .signature(new ObjectKey(file.lastModified()))
                    .listener(settle(placeholder))
                    .error(R.drawable.ic_video)
                    .into(view);
            return;
        }

        // Poster URLs from Instagram and Facebook CDNs are refused without a User-Agent and
        // Referer, which is exactly why an unadorned image load shows an empty preview.
        Glide.with(view)
                .load(withHeaders(source, headers))
                .listener(settle(placeholder))
                .error(R.drawable.ic_video)
                .into(view);
    }

    /** Hides the placeholder whether the image arrived or failed; either way, waiting is over. */
    private static RequestListener<Drawable> settle(@Nullable View placeholder) {
        return new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                        Target<Drawable> target, boolean isFirstResource) {
                hide(placeholder);
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                           DataSource dataSource, boolean isFirstResource) {
                hide(placeholder);
                return false;
            }
        };
    }

    private static void show(@Nullable View placeholder) {
        if (placeholder != null) placeholder.setVisibility(View.VISIBLE);
    }

    private static void hide(@Nullable View placeholder) {
        if (placeholder != null) placeholder.setVisibility(View.GONE);
    }

    private static Object withHeaders(String url, Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return url;
        LazyHeaders.Builder builder = new LazyHeaders.Builder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            // Cookies belong to the page, not the image CDN, and some reject the request when
            // one arrives. User-Agent and Referer are what actually matter here.
            if (entry.getKey().equalsIgnoreCase("Cookie")) continue;
            builder.addHeader(entry.getKey(), entry.getValue());
        }
        return new GlideUrl(url, builder.build());
    }

    /** Stops any in-flight Glide request so it cannot overwrite a bitmap we set directly. */
    private static void clear(ImageView view) {
        try {
            Glide.with(view).clear(view);
        } catch (Exception ignored) {
        }
    }

    private static Bitmap decode(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            if (comma < 0) return null;
            byte[] bytes = Base64.decode(dataUri.substring(comma + 1), Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) {
            return null;
        }
    }
}
