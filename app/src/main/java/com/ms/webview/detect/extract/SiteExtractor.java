package com.ms.webview.detect.extract;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * Reads one platform's JSON shape.
 *
 * <p>The walker visits every object in a captured response and hands it to each applicable
 * extractor, so an implementation only has to recognise the small node it cares about — it
 * never has to know the surrounding envelope, which is the part these platforms reshuffle
 * constantly.
 */
public interface SiteExtractor {

    /** Lower-case host of the page. Return true for any host you want to inspect. */
    boolean appliesTo(String host);

    /** Called for every JSON object in the response. Append anything recognised to {@code out}. */
    void inspect(JsonObject node, String pageUrl, List<FoundMedia> out);
}
