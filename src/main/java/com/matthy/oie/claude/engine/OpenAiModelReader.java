/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.server.ModelReader;

/** The models of an OpenAI-compatible provider: GET {baseUrl}/models (OpenAI, Ollama, vLLM). */
public class OpenAiModelReader implements ModelReader {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;

    public OpenAiModelReader(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
    }

    @Override
    public String list(String apiKey) throws Exception {
        String url = OpenAiCompatibleLoop.endpoint(baseUrl, "/models");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).GET();
        if (apiKey != null && !apiKey.isBlank()) {
            request.header("Authorization", "Bearer " + apiKey.trim());
        }
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(OpenAiCompatibleLoop.describe(e));
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(failure(response.statusCode(), url));
        }
        return parse(response.body());
    }

    /** Why the model list could not be loaded from {@code url}, with a hint where one helps. */
    static String failure(int status, String url) {
        if (status == 401) {
            return "The model server did not accept the API key (HTTP 401), so the model list could not be loaded.";
        }
        if (status == 404) {
            return "The model server has no model list at " + url + " (HTTP 404). Check the API base URL: it usually ends in /v1, for example https://api.openai.com/v1.";
        }
        return "Loading the model list from " + url + " failed (HTTP " + status + ").";
    }

    /** OpenAI's {"data":[{"id","created" (epoch seconds)}]} as the array ModelList expects. */
    static String parse(String body) throws Exception {
        ArrayNode out = JSON.createArrayNode();
        for (JsonNode item : JSON.readTree(body).path("data")) {
            String id = item.path("id").asText("");
            if (id.isEmpty()) {
                continue;
            }
            ObjectNode model = out.addObject().put("id", id).put("displayName", id);
            if (item.path("created").canConvertToLong() && item.path("created").asLong() > 0) {
                model.put("createdAt", Instant.ofEpochSecond(item.path("created").asLong()).toString());
            }
        }
        return out.toString();
    }
}
