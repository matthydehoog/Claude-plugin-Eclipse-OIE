/* This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. */
package com.matthy.oie.claude.engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        if (anthropic) request.header("x-api-key", apiKey).header("anthropic-version", "2023-06-01");
        else if (apiKey != null && !apiKey.isBlank()) request.header("Authorization", "Bearer " + apiKey);
        HttpResponse<String> response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Loading models failed (HTTP " + response.statusCode() + "): " + response.body());
        JsonNode root = JSON.readTree(response.body());
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode item : root.path("data")) {
            String id = item.path("id").asText();
            if (!id.isEmpty()) out.addObject().put("id", id).put("displayName", item.path(anthropic ? "display_name" : "name").asText(id));
        }
        com.fasterxml.jackson.databind.node.ObjectNode result = JSON.createObjectNode();
        result.set("models", out);
        result.put("source", anthropic ? "anthropic" : "openai-compatible");
        return result.toString();
    }
}
