/* This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package com.matthy.oie.claude.engine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.matthy.oie.claude.server.ModelReader;

/** Reads model IDs from OpenAI-compatible /v1/models endpoints and Anthropic's model list. */
public class SdkModelReader implements ModelReader {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Override public String list(String apiKey, String baseUrl, String provider) throws Exception {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        boolean anthropic = "anthropic".equals(provider);
        String url = base.endsWith("/v1") ? base + "/models" : base + "/v1/models";
        ArrayNode out = JSON.createArrayNode();
        if (anthropic) {
            String afterId = null;
            Set<String> seenCursors = new HashSet<>();
            boolean hasMore;
            do {
                String pageUrl = url + "?limit=1000" + (afterId == null ? "" : "&after_id=" + URLEncoder.encode(afterId, StandardCharsets.UTF_8));
                JsonNode root = JSON.readTree(send(pageUrl, apiKey, true));
                appendModels(out, root, true);
                hasMore = root.path("has_more").asBoolean(false);
                if (!hasMore) break;

                String nextCursor = root.path("last_id").asText("");
                if (nextCursor.isEmpty() || !seenCursors.add(nextCursor)) {
                    throw new IllegalStateException("Loading Anthropic models failed: pagination did not provide a new last_id.");
                }
                afterId = nextCursor;
            } while (true);
        } else {
            JsonNode root = JSON.readTree(send(url, apiKey, false));
            appendModels(out, root, false);
        }
        com.fasterxml.jackson.databind.node.ObjectNode result = JSON.createObjectNode();
        result.set("models", out);
        result.put("source", anthropic ? "anthropic" : "openai-compatible");
        return result.toString();
    }

    private String send(String url, String apiKey, boolean anthropic) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (anthropic) request.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        else if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Loading models failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        return response.body();
    }

    private static void appendModels(ArrayNode out, JsonNode root, boolean anthropic) {
        for (JsonNode item : root.path("data")) {
            String id = item.path("id").asText();
            if (!id.isEmpty()) out.addObject().put("id", id).put("displayName", item.path(anthropic ? "display_name" : "name").asText(id));
        }
    }
}
