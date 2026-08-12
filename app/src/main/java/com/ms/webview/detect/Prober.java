package com.ms.webview.detect;

import androidx.annotation.Nullable;

import com.ms.webview.core.Http;

import java.io.IOException;
import java.util.Map;

import okhttp3.Request;
import okhttp3.Response;

/**
 * Confirms what a URL actually serves. {@code shouldInterceptRequest} never exposes the response
 * headers, so anything we could not classify from the URL alone gets one cheap out-of-band
 * request here.
 *
 * <p>It also settles which headers the CDN will accept. Finding that out now, while the sheet
 * is still being built, is much better than finding out during a download that has already
 * shown the user a progress bar.
 */
public class Prober {

    public static class Result {
        public final MediaKind kind;
        public final String mime;
        public final long contentLength;
        public final boolean acceptsRanges;
        /** The header set that got a successful response, or null if the original set worked. */
        @Nullable
        public final Map<String, String> workingHeaders;

        Result(MediaKind kind, String mime, long contentLength, boolean acceptsRanges,
               @Nullable Map<String, String> workingHeaders) {
            this.kind = kind;
            this.mime = mime;
            this.contentLength = contentLength;
            this.acceptsRanges = acceptsRanges;
            this.workingHeaders = workingHeaders;
        }
    }

    /**
     * Tries HEAD first, then a one-byte ranged GET. Plenty of media CDNs reject HEAD outright,
     * and a {@code Range: bytes=0-0} costs nothing while also telling us whether ranged
     * downloads — and therefore resume — will work at all. If both are refused on
     * authentication grounds, the whole sequence is retried with relaxed headers.
     */
    @Nullable
    public Result probe(String url, Map<String, String> headers) {
        Attempt attempt = attempt(url, headers, null);
        if (attempt.denied) {
            Map<String, String> relaxed = Http.relaxed(headers);
            Attempt retry = attempt(url, relaxed, relaxed);
            if (retry.result != null) return retry.result;
        }
        return attempt.result;
    }

    private static class Attempt {
        @Nullable
        Result result;
        boolean denied;
    }

    private Attempt attempt(String url, Map<String, String> headers,
                            @Nullable Map<String, String> workingHeaders) {
        Attempt attempt = new Attempt();

        Outcome head = request(url, headers, true, workingHeaders);
        if (head.result != null && (head.result.mime != null || head.result.contentLength > 0)) {
            attempt.result = head.result;
            return attempt;
        }

        Outcome ranged = request(url, headers, false, workingHeaders);
        attempt.result = ranged.result != null ? ranged.result : head.result;
        attempt.denied = attempt.result == null && (head.denied || ranged.denied);
        return attempt;
    }

    private static class Outcome {
        @Nullable
        Result result;
        boolean denied;
    }

    private Outcome request(String url, Map<String, String> headers, boolean head,
                            @Nullable Map<String, String> workingHeaders) {
        Outcome outcome = new Outcome();

        Request.Builder b = new Request.Builder().url(url);
        Http.withCaptured(b, headers);
        if (head) {
            b.head();
        } else {
            b.get().header("Range", "bytes=0-0");
        }

        try (Response resp = Http.client().newCall(b.build()).execute()) {
            if (!resp.isSuccessful() && resp.code() != 206) {
                outcome.denied = Http.deniedByHeaders(resp.code());
                return outcome;
            }

            String mime = resp.header("Content-Type");
            if (mime != null) {
                int semi = mime.indexOf(';');
                if (semi > 0) mime = mime.substring(0, semi).trim();
            }

            long length = -1;
            boolean ranges = "bytes".equalsIgnoreCase(String.valueOf(resp.header("Accept-Ranges")));
            String contentRange = resp.header("Content-Range");
            if (contentRange != null) {
                // "bytes 0-0/12345"
                int slash = contentRange.lastIndexOf('/');
                if (slash > 0) {
                    try {
                        length = Long.parseLong(contentRange.substring(slash + 1).trim());
                        ranges = true;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (length < 0) {
                String cl = resp.header("Content-Length");
                if (cl != null) {
                    try {
                        length = Long.parseLong(cl.trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            MediaKind kind = UrlClassifier.fromMime(mime);
            if (UrlClassifier.isNonMediaMime(mime)) {
                // The server said image/text/audio. No amount of URL shape overrides that.
                outcome.result = new Result(MediaKind.NONE, mime, length, ranges, workingHeaders);
                return outcome;
            }
            if (kind == MediaKind.NONE || kind == MediaKind.UNKNOWN) {
                // Fall back to the URL shape when the server is vague about the type.
                MediaKind byUrl = UrlClassifier.classify(url);
                if (byUrl == MediaKind.UNKNOWN && kind == MediaKind.UNKNOWN) {
                    kind = MediaKind.PROGRESSIVE;   // octet-stream at a video-ish path
                } else if (byUrl != MediaKind.NONE && byUrl != MediaKind.UNKNOWN) {
                    kind = byUrl;
                }
            }
            outcome.result = new Result(kind, mime, length, ranges, workingHeaders);
            return outcome;

        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            return outcome;
        }
    }
}
