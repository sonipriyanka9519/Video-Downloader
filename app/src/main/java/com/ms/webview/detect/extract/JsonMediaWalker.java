package com.ms.webview.detect.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Walks a captured JSON response and offers every object in it to the applicable extractors.
 *
 * <p>Iterative rather than recursive, and bounded: these payloads are machine-generated and a
 * feed response can be megabytes of deeply nested nodes.
 */
public final class JsonMediaWalker {

    private static final int MAX_NODES = 40_000;
    private static final int MAX_DOCUMENTS = 24;

    private JsonMediaWalker() {
    }

    public static List<FoundMedia> walk(String json, String pageUrl, List<SiteExtractor> extractors) {
        List<FoundMedia> found = new ArrayList<>();
        if (json == null || json.isEmpty() || extractors.isEmpty()) return found;

        for (JsonElement root : parseAll(json)) {
            walkOne(root, pageUrl, extractors, found);
        }
        return found;
    }

    /**
     * Facebook in particular answers with several top-level JSON objects concatenated in one
     * body, which a strict parse rejects outright — dropping the whole response. A lenient
     * reader pulls out each document in turn.
     */
    private static List<JsonElement> parseAll(String json) {
        List<JsonElement> roots = new ArrayList<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(true);
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                roots.add(JsonParser.parseReader(reader));
                if (roots.size() >= MAX_DOCUMENTS) break;
            }
        } catch (Exception e) {
            // Truncated or not JSON after all; keep whatever parsed cleanly.
        }
        return roots;
    }

    private static void walkOne(JsonElement root, String pageUrl,
                                List<SiteExtractor> extractors, List<FoundMedia> found) {
        Deque<JsonElement> stack = new ArrayDeque<>();
        stack.push(root);
        int visited = 0;

        while (!stack.isEmpty() && visited < MAX_NODES) {
            JsonElement current = stack.pop();
            visited++;

            if (current.isJsonArray()) {
                JsonArray array = current.getAsJsonArray();
                for (JsonElement child : array) {
                    if (child.isJsonObject() || child.isJsonArray()) stack.push(child);
                }
            } else if (current.isJsonObject()) {
                JsonObject object = current.getAsJsonObject();
                for (SiteExtractor extractor : extractors) {
                    try {
                        extractor.inspect(object, pageUrl, found);
                    } catch (Exception ignored) {
                        // A malformed node must not abort the whole walk.
                    }
                }
                for (String key : object.keySet()) {
                    JsonElement child = object.get(key);
                    if (child != null && (child.isJsonObject() || child.isJsonArray())) {
                        stack.push(child);
                    }
                }
            }
        }
    }
}
