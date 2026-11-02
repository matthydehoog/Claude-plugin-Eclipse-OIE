/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 */
package com.matthy.oie.claude.engine;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.server.ChatJob;
import com.matthy.oie.claude.server.ModelLoop;

/** OpenAI Chat Completions adapter for OpenAI-compatible endpoints such as Ollama and vLLM. */
public final class OpenAiCompatibleLoop implements ModelLoop {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey, baseUrl, model, systemPrompt;
    private final int maxToolCalls;
    private final List<Map<String, Object>> toolSpecs;
    private final ToolCaller caller;

    @SuppressWarnings("unchecked")
    public OpenAiCompatibleLoop(String apiKey, String baseUrl, String model, String effort, int maxToolCalls,
            String systemPrompt, List toolSpecs, ToolCaller caller) {
        this.apiKey = apiKey == null ? "" : apiKey;
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.model = model;
        this.maxToolCalls = maxToolCalls;
        this.systemPrompt = systemPrompt;
        this.toolSpecs = (List<Map<String, Object>>) toolSpecs;
        this.caller = caller;
    }

    @Override public void close() { }
    @Override public String saveHistory(Object history) throws Exception { return history == null ? null : JSON.writeValueAsString(history); }
    @Override public Object loadHistory(String json) throws Exception { return JSON.readTree(json); }

    @Override public void run(ChatJob job, String userText) {
        try {
            ArrayNode messages;
            Object old = job.conversation().history();
            if (old instanceof JsonNode && ((JsonNode) old).isArray()) messages = (ArrayNode) ((JsonNode) old).deepCopy();
            else messages = JSON.createArrayNode();
            if (messages.isEmpty()) messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userText);
            int calls = 0;
            while (!job.isCancelled()) {
                ObjectNode body = JSON.createObjectNode().put("model", model).put("stream", false);
                body.set("messages", messages);
                if (!toolSpecs.isEmpty()) body.set("tools", toolsJson());
                HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(completionsUrl()))
                        .timeout(Duration.ofMinutes(10)).header("Content-Type", "application/json");
                if (!apiKey.isEmpty()) request.header("Authorization", "Bearer " + apiKey);
                HttpResponse<String> response = http.send(request.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("OpenAI-compatible endpoint returned HTTP " + response.statusCode() + ": " + response.body());
                JsonNode message = JSON.readTree(response.body()).path("choices").path(0).path("message");
                if (message.isMissingNode()) throw new IllegalStateException("Endpoint returned no assistant message.");
                messages.add(message);
                String content = message.path("content").isTextual() ? message.path("content").asText() : "";
                job.appendPartial(content);
                JsonNode toolCalls = message.path("tool_calls");
                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    job.emit("text", content);
            job.conversation().setHistory(messages);
                    job.finish(ChatJob.State.DONE);
                    return;
                }
                for (JsonNode callNode : toolCalls) {
                    if (++calls > maxToolCalls) throw new IllegalStateException("The maximum number of tool calls per question was reached.");
                    String id = callNode.path("id").asText();
                    String name = callNode.path("function").path("name").asText();
                    String args = callNode.path("function").path("arguments").asText("{}");
                    String result = caller.call(job, name, args);
                    messages.addObject().put("role", "tool").put("tool_call_id", id).put("content", result == null ? "" : result);
                }
                job.clearPartial();
            }
            job.finish(ChatJob.State.CANCELLED);
        } catch (Throwable error) {
            if (!job.isCancelled()) {
                job.clearPartial();
                job.emit("error", "OpenAI-compatible API error: " + error.getMessage());
                job.finish(ChatJob.State.ERROR);
            }
        }
    }

    private ArrayNode toolsJson() {
        ArrayNode out = JSON.createArrayNode();
        for (Map<String, Object> spec : toolSpecs) {
            ObjectNode fn = JSON.createObjectNode().put("name", String.valueOf(spec.get("name"))).put("description", String.valueOf(spec.get("description")));
            ObjectNode params = JSON.createObjectNode().put("type", "object");
            params.set("properties", JSON.valueToTree(spec.get("properties")));
            params.set("required", JSON.valueToTree(spec.get("required")));
            fn.set("parameters", params);
            out.addObject().put("type", "function").set("function", fn);
        }
        return out;
    }

    private String completionsUrl() {
        return baseUrl.endsWith("/v1") ? baseUrl + "/chat/completions" : baseUrl + "/v1/chat/completions";
    }
}
