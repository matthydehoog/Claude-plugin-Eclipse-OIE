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
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.server.ChatJob;
import com.matthy.oie.claude.server.Conversation;
import com.matthy.oie.claude.server.ModelLoop;

/**
 * The model/tool loop for OpenAI-compatible Chat Completions APIs (OpenAI, Ollama, vLLM and
 * others), with either Chat Completions or Responses as selected in the settings.
 * Masking, review and approval happen on the host side in the {@link ModelLoop.ToolCaller},
 * as for Claude.
 *
 * Originally contributed by Miguel Duarte (SoulOnFire) in PR #34.
 */
public final class OpenAiCompatibleLoop implements ModelLoop {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Marks a saved conversation as this loop's, so Claude's and this loop's histories are never mixed. */
    private static final String HISTORY_KEY = "openAiMessages";
    private static final String RESPONSES_HISTORY_KEY = "openAiResponseItems";
    private static final int MAX_ERROR_BODY = 500;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String apiKey;
    private final String baseUrl;
    private final String model;
    private final int maxToolCalls;
    private final String systemPrompt;
    private final ArrayNode tools;
    private final boolean responsesApi;
    private final String historyKey;
    private final ToolCaller caller;

    /** @param toolSpecs maps with name, description, properties (JSON schema per property) and required */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public OpenAiCompatibleLoop(String apiKey, String baseUrl, String model, int maxToolCalls, String systemPrompt, List toolSpecs, ToolCaller caller) {
        this(apiKey, baseUrl, model, false, maxToolCalls, systemPrompt, toolSpecs, caller);
    }

    /**
     * Erstellt die Modellschleife mit dem ausdruecklich konfigurierten API-Modus.
     *
     * @param apiKey optionaler API-Schluessel
     * @param baseUrl Basisadresse des Modellservers
     * @param model Modell-ID ohne Einfluss auf die Endpunktwahl
     * @param responsesApi true fuer Responses, false fuer Chat Completions
     * @param maxToolCalls maximale Tool-Aufrufe je Frage
     * @param systemPrompt Systemanweisung fuer das Modell
     * @param toolSpecs vorhandene OIE-Tool-Schemas
     * @param caller bestehender Aufrufpfad mit Pruefung und Freigabe der Tools
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public OpenAiCompatibleLoop(String apiKey, String baseUrl, String model, boolean responsesApi, int maxToolCalls, String systemPrompt, List toolSpecs, ToolCaller caller) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.model = model;
        this.maxToolCalls = maxToolCalls;
        this.systemPrompt = systemPrompt;
        this.responsesApi = responsesApi;
        this.historyKey = responsesApi ? RESPONSES_HISTORY_KEY : HISTORY_KEY;
        this.tools = toolsJson((List<Map<String, Object>>) toolSpecs, responsesApi);
        this.caller = caller;
    }

    /** {baseUrl}/chat/completions; a base URL without /v1 (e.g. http://localhost:11434) gets it added. */
    static String endpoint(String baseUrl, String path) {
        String base = baseUrl.replaceAll("/+$", "");
        return (base.matches(".*/v\\d+$") ? base : base + "/v1") + path;
    }

    /**
     * Erstellt die gemeinsamen Request-Felder fuer Chat und Verbindungstest.
     * Responses werden ohne serverseitige Speicherung angefordert; Reasoning bleibt aktiv.
     *
     * @param model zu verwendende Modell-ID
     * @param responsesApi konfigurierter API-Modus
     * @return neue JSON-Nutzlast mit Modell, Streaming- und gegebenenfalls Speicheroption
     */
    static ObjectNode requestBody(String model, boolean responsesApi) {
        ObjectNode body = JSON.createObjectNode().put("model", model).put("stream", false);
        if (responsesApi) {
            body.put("store", false);
        }
        return body;
    }

    @Override
    public void close() {
        // The JDK HttpClient needs no explicit close (Java 11).
    }

    @Override
    public String saveHistory(Object history) throws Exception {
        if (!(history instanceof ObjectNode)) {
            return null;
        }
        JsonNode items = ((ObjectNode) history).path(historyKey);
        if (!items.isArray()) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        synchronized (items) {
            out.set(historyKey, items.deepCopy());
        }
        return JSON.writeValueAsString(out);
    }

    /** Laedt nur die Historie des gewaehlten API-Modus; andere Formate beginnen ohne Modellkontext. */
    @Override
    public Object loadHistory(String json) throws Exception {
        JsonNode saved = JSON.readTree(json);
        return saved.isObject() && saved.path(historyKey).isArray() ? saved : null;
    }

    @Override
    public void run(ChatJob job, String userText) {
        try {
            loop(job, userText);
        } catch (Throwable t) {
            if (!job.isCancelled()) {
                job.clearPartial();
                job.emit("error", describe(t));
                job.finish(ChatJob.State.ERROR);
            }
        }
    }

    private void loop(ChatJob job, String userText) throws Exception {
        ArrayNode history = history(job.conversation());
        synchronized (history) {
            history.addObject().put("role", "user").put("content", userText);
        }
        int toolCalls = 0;
        boolean limitReached = false;
        while (!job.isCancelled()) {
            JsonNode message = complete(history, limitReached);
            if (job.isCancelled()) {
                return;
            }
            String content = message.path("content").isTextual() ? message.path("content").asText() : "";
            JsonNode calls = message.path("tool_calls");
            if (!content.isBlank()) {
                job.emit("text", content);
            }
            // Only the fields the API accepts back (OpenAI rejects some response-only fields).
            ObjectNode answer = JSON.createObjectNode().put("role", "assistant");
            if (content.isEmpty()) {
                answer.putNull("content");
            } else {
                answer.put("content", content);
            }
            if (calls.isArray() && calls.size() > 0) {
                answer.set("tool_calls", calls);
            }

            if (!calls.isArray() || calls.size() == 0) {
                synchronized (history) {
                    appendAnswer(history, answer, message);
                }
                if ("length".equals(message.path("finish_reason").asText(""))) {
                    job.emit("info", "The answer was cut off because it got too long. Ask for a continuation if needed.");
                }
                job.finish(ChatJob.State.DONE);
                return;
            }

            // Every tool call needs a tool message with its id before the next request.
            ArrayNode results = JSON.createArrayNode();
            for (JsonNode call : calls) {
                String result;
                if (limitReached || job.isCancelled()) {
                    result = "Not run: the limit of " + maxToolCalls + " tool calls per question has been reached. Answer now with the information you have.";
                } else {
                    toolCalls++;
                    try {
                        String arguments = call.path("function").path("arguments").asText("");
                        result = caller.call(job, call.path("function").path("name").asText(), arguments.isBlank() ? "{}" : arguments);
                    } catch (Exception e) {
                        result = "Error: " + (e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }
                if (responsesApi) {
                    results.addObject().put("type", "function_call_output").put("call_id", call.path("id").asText()).put("output", result == null ? "" : result);
                } else {
                    results.addObject().put("role", "tool").put("tool_call_id", call.path("id").asText()).put("content", result == null ? "" : result);
                }
            }
            synchronized (history) {
                appendAnswer(history, answer, message);
                history.addAll(results);
            }
            if (limitReached) {
                job.emit("info", "Stopped after " + maxToolCalls + " tool calls. Ask a follow-up question to continue.");
                job.finish(ChatJob.State.DONE);
                return;
            }
            if (toolCalls >= maxToolCalls) {
                // One more round, without tools, so the model can answer with what it has.
                limitReached = true;
            }
        }
    }

    /**
     * Fuegt die Antwort im Format des Endpunkts zur vom Aufrufer gesperrten Historie hinzu.
     * Responses-Items einschliesslich verschluesseltem Reasoning werden unveraendert erhalten.
     *
     * @param history bereits synchronisierte Gespraechshistorie
     * @param answer normalisierte Chat-Completions-Nachricht als Rueckfall
     * @param message Modellantwort mit optionalen vollstaendigen Responses-Ausgabeitems
     */
    private static void appendAnswer(ArrayNode history, ObjectNode answer, JsonNode message) {
        JsonNode output = message.path("response_output");
        if (output.isArray()) {
            history.addAll((ArrayNode) output);
        } else {
            history.add(answer);
        }
    }

    /**
     * Sendet die aktuelle Historie an den zum Modell passenden Endpunkt.
     *
     * @param history bisherige Nachrichten oder Responses-Items
     * @param withoutTools true fuer die abschliessende Antwort nach dem Tool-Limit
     * @return normalisierte Antwort mit Text, Tool-Aufrufen und Abschlussgrund
     * @throws Exception bei Transport-, HTTP- oder Antwortformatfehlern
     */
    private JsonNode complete(ArrayNode history, boolean withoutTools) throws Exception {
        ObjectNode body = requestBody(model, responsesApi);
        ArrayNode messages = body.putArray(responsesApi ? "input" : "messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        synchronized (history) {
            messages.addAll(history.deepCopy());
        }
        if (tools.size() > 0 && !withoutTools) {
            body.set("tools", tools);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint(baseUrl, responsesApi ? "/responses" : "/chat/completions")))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (!apiKey.isEmpty()) {
            request.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ApiException(response.statusCode(), response.body());
        }
        return assistantMessage(JSON.readTree(response.body()), responsesApi);
    }

    /**
     * Normalisiert Antworten beider Endpunkte fuer Tool-Schleife und Verbindungstest.
     * Responses-Ausgabeitems bleiben fuer die naechste Anfrage vollstaendig erhalten.
     * Fehlgeschlagene Antworten und unvollstaendige Tool-Aufrufe werden nicht ausgefuehrt.
     *
     * @param response geparste Antwort des Modellservers
     * @param responsesApi true fuer das Responses-Format, sonst Chat Completions
     * @return Nachricht mit Text, Tool-Aufrufen und Abschlussgrund
     * @throws IllegalStateException bei fehlenden oder nicht verwendbaren Antwortdaten
     */
    static ObjectNode assistantMessage(JsonNode response, boolean responsesApi) {
        if (!responsesApi) {
            JsonNode choice = response.path("choices").path(0);
            if (!choice.path("message").isObject()) {
                throw new IllegalStateException("The model server returned no answer: " + shorten(response.toString()));
            }
            ObjectNode message = ((ObjectNode) choice.path("message")).deepCopy();
            message.put("finish_reason", choice.path("finish_reason").asText(""));
            return message;
        }

        String status = response.path("status").asText();
        if (!"completed".equals(status) && !"incomplete".equals(status)) {
            throw new IllegalStateException("The model server returned response status '" + status + "': "
                    + shorten(response.path("error").path("message").asText("No completed response.")));
        }
        JsonNode output = response.path("output");
        if (!output.isArray()) {
            throw new IllegalStateException("The model server returned no answer: " + shorten(response.toString()));
        }
        ObjectNode message = JSON.createObjectNode();
        message.set("response_output", output);
        ArrayNode calls = message.putArray("tool_calls");
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            if ("function_call".equals(item.path("type").asText())) {
                ObjectNode call = calls.addObject().put("id", item.path("call_id").asText()).put("type", "function");
                call.putObject("function").put("name", item.path("name").asText()).put("arguments", item.path("arguments").asText());
            } else if ("message".equals(item.path("type").asText())) {
                for (JsonNode part : item.path("content")) {
                    if ("output_text".equals(part.path("type").asText())) {
                        text.append(part.path("text").asText());
                    } else if ("refusal".equals(part.path("type").asText())) {
                        text.append(part.path("refusal").asText());
                    }
                }
            }
        }
        if ("incomplete".equals(status) && (calls.size() > 0
                || !"max_output_tokens".equals(response.path("incomplete_details").path("reason").asText()))) {
            throw new IllegalStateException("The model response was incomplete; no tool calls were executed.");
        }
        message.put("content", text.toString());
        message.put("finish_reason", "incomplete".equals(status) ? "length" : "stop");
        return message;
    }

    /**
     * Liefert die zum API-Modus passende Historie und beginnt nach einem Moduswechsel neu.
     *
     * @param conversation Gespraech mit opaker, gegebenenfalls anders formatierter Historie
     * @return Nachrichtenarray fuer den aktuellen Modus; fremde Formate werden ersetzt
     */
    private ArrayNode history(Conversation conversation) {
        synchronized (conversation) {
            Object current = conversation.history();
            if (!(current instanceof ObjectNode) || !((ObjectNode) current).path(historyKey).isArray()) {
                ObjectNode history = JSON.createObjectNode();
                history.putArray(historyKey);
                conversation.setHistory(history);
            }
            return (ArrayNode) ((ObjectNode) conversation.history()).get(historyKey);
        }
    }

    private static ArrayNode toolsJson(List<Map<String, Object>> specs, boolean responsesApi) {
        ArrayNode out = JSON.createArrayNode();
        if (specs == null) {
            return out;
        }
        for (Map<String, Object> spec : specs) {
            ObjectNode function = JSON.createObjectNode().put("name", String.valueOf(spec.get("name"))).put("description", String.valueOf(spec.get("description")));
            ObjectNode parameters = function.putObject("parameters").put("type", "object");
            parameters.set("properties", spec.get("properties") == null ? JSON.createObjectNode() : JSON.valueToTree(spec.get("properties")));
            if (spec.get("required") != null) {
                parameters.set("required", JSON.valueToTree(spec.get("required")));
            }
            if (responsesApi) {
                function.put("type", "function").put("strict", false);
                out.add(function);
            } else {
                out.addObject().put("type", "function").set("function", function);
            }
        }
        return out;
    }

    static String describe(Throwable t) {
        if (t instanceof ApiException) {
            ApiException e = (ApiException) t;
            if (e.status == 401) {
                return "The model server did not accept the API key (HTTP 401). Change it under Settings > AI Assistant.";
            }
            if (e.status == 404) {
                return "The model server returned HTTP 404: check the API base URL and the model ID. " + shorten(e.body);
            }
            if (e.status == 429) {
                return "Too many requests or no credit left at the model server (HTTP 429). " + shorten(e.body);
            }
            return "Model server error (HTTP " + e.status + "): " + shorten(e.body);
        }
        if (t instanceof java.io.IOException) {
            Throwable root = t;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            return "Cannot reach the model server from the OIE server (" + root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage())
                    + "). Check the API base URL, the firewall or proxy.";
        }
        return "Unexpected error: " + t;
    }

    private static String shorten(String body) {
        String text = body == null ? "" : body.trim();
        return text.length() > MAX_ERROR_BODY ? text.substring(0, MAX_ERROR_BODY) + "…" : text;
    }

    static final class ApiException extends Exception {
        private static final long serialVersionUID = 1L;
        final int status;
        final String body;

        ApiException(int status, String body) {
            super("HTTP " + status);
            this.status = status;
            this.body = body;
        }
    }
}
