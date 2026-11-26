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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.server.ConnectionTest;
import com.matthy.oie.claude.server.ConnectionTester;

/**
 * Test connection for OpenAI-compatible providers (OpenAI, Mistral, Ollama, vLLM): GET {baseUrl}/models,
 * then one model request with one function, using the same API as {@link OpenAiCompatibleLoop}.
 */
public final class OpenAiConnectionTester implements ConnectionTester {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BODY = 2000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String baseUrl;
    private final boolean responsesApi;

    public OpenAiConnectionTester(String baseUrl) {
        this(baseUrl, false);
    }

    /**
     * Erstellt den Verbindungstest fuer den gespeicherten API-Modus.
     *
     * @param baseUrl Basisadresse des Modellservers
     * @param responsesApi true fuer Responses, false fuer Chat Completions
     */
    public OpenAiConnectionTester(String baseUrl, boolean responsesApi) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.responsesApi = responsesApi;
    }

    @Override
    public String probe(String apiKey, String model) throws Exception {
        String key = apiKey == null ? "" : apiKey.trim();
        ObjectNode out = JSON.createObjectNode();
        String modelsUrl = OpenAiCompatibleLoop.endpoint(baseUrl, "/models");
        try {
            out.put("server", URI.create(modelsUrl).getHost());
        } catch (IllegalArgumentException e) {
            out.put("server", baseUrl);
        }

        ObjectNode list = out.putObject("list");
        try {
            HttpResponse<String> response = http.send(authorized(HttpRequest.newBuilder(URI.create(modelsUrl)), key).timeout(Duration.ofSeconds(30)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            list.put("status", response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                ArrayNode ids = list.putArray("models");
                try {
                    for (JsonNode m : JSON.readTree(OpenAiModelReader.parse(response.body()))) {
                        ids.add(m.path("id").asText());
                    }
                } catch (Exception e) {
                    // Not a model list (an HTML page, say): the test request will tell more.
                }
            } else {
                list.put("body", shorten(response.body()));
            }
        } catch (Exception e) {
            list.put("error", unreachable(e));
            return out.toString();
        }
        int status = list.path("status").asInt();
        if (status == 401 || status == 403 || model == null || model.isBlank()) {
            return out.toString();
        }

        ObjectNode body = OpenAiCompatibleLoop.requestBody(model, responsesApi);
        body.putArray(responsesApi ? "input" : "messages").addObject().put("role", "user").put("content", ConnectionTest.PROMPT);
        ObjectNode definition = body.putArray("tools").addObject().put("type", "function");
        ObjectNode function = responsesApi ? definition.put("strict", false) : definition.putObject("function");
        function.put("name", ConnectionTest.TOOL_NAME).put("description", ConnectionTest.TOOL_DESCRIPTION);
        ObjectNode parameters = function.putObject("parameters").put("type", "object");
        parameters.set("properties", JSON.valueToTree(ConnectionTest.TOOL_PROPERTIES));
        parameters.putArray("required").add("status");

        ObjectNode tool = out.putObject("tool");
        try {
            // A local server may first have to load the model, so allow more time than for the list.
            HttpRequest request = authorized(HttpRequest.newBuilder(URI.create(OpenAiCompatibleLoop.endpoint(baseUrl, responsesApi ? "/responses" : "/chat/completions"))), key)
                    .timeout(Duration.ofMinutes(2)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            tool.put("status", response.statusCode());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                JsonNode message = OpenAiCompatibleLoop.assistantMessage(JSON.readTree(response.body()), responsesApi);
                tool.put("toolCalled", toolCalled(message));
                tool.put("text", shorten(message.path("content").asText("")));
            } else {
                tool.put("body", shorten(response.body()));
            }
        } catch (Exception e) {
            tool.put("error", unreachable(e));
        }
        return out.toString();
    }

    /** Whether the answer calls the test function (tool_calls, or the legacy function_call). */
    static boolean toolCalled(JsonNode message) {
        for (JsonNode call : message.path("tool_calls")) {
            if (ConnectionTest.TOOL_NAME.equals(call.path("function").path("name").asText())) {
                return true;
            }
        }
        return ConnectionTest.TOOL_NAME.equals(message.path("function_call").path("name").asText());
    }

    private String unreachable(Exception e) {
        if (e instanceof IllegalArgumentException) {
            return "The API base URL '" + baseUrl + "' is not a valid URL (" + e.getMessage() + ").";
        }
        if (e instanceof java.net.http.HttpTimeoutException) {
            return "The model server did not answer in time (" + e.getMessage() + ").";
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return "The test was interrupted.";
        }
        return OpenAiCompatibleLoop.describe(e);
    }

    private static HttpRequest.Builder authorized(HttpRequest.Builder request, String key) {
        return key.isEmpty() ? request : request.header("Authorization", "Bearer " + key);
    }

    private static String shorten(String text) {
        String t = text == null ? "" : text.trim();
        return t.length() > MAX_BODY ? t.substring(0, MAX_BODY) : t;
    }
}
