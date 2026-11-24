/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test connection in the AI Assistant settings: three checks (reachable, key accepted, model with
 * tool calling) worked out from what a {@link ConnectionTester} saw. Only a fixed prompt is sent.
 */
public final class ConnectionTest {

    /** The only text sent to the model. */
    public static final String PROMPT = "This is a connection test from the AI Assistant plugin of Open Integration Engine. Call the tool "
            + "report_test_result with status \"ok\". Do not write anything else.";
    public static final String TOOL_NAME = "report_test_result";
    public static final String TOOL_DESCRIPTION = "Reports the result of the connection test.";
    /** JSON schema of the tool's one property. */
    public static final Map<String, Object> TOOL_PROPERTIES = Map.of("status", Map.of("type", "string", "description", "Always \"ok\"."));

    public static final String OK = "ok", FAILED = "failed", WARNING = "warning", SKIPPED = "skipped";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_DETAIL = 300;

    private ConnectionTest() {
    }

    /**
     * @param probe  what the tester saw (see {@link ConnectionTester#probe})
     * @param model  the model in the settings
     * @param openAi whether the provider is OpenAI-compatible (only changes the wording)
     * @return {ok, checks: [{id, label, status, message}]}
     */
    public static ObjectNode report(JsonNode probe, String model, boolean openAi) {
        String server = probe.path("server").asText(openAi ? "the model server" : "Anthropic");
        JsonNode list = probe.path("list");
        JsonNode tool = probe.path("tool");
        String[] reachable, key, checked;

        if (list.has("error")) {
            reachable = check(FAILED, list.path("error").asText());
            String notChecked = "Not checked: " + server + " could not be reached.";
            return build(reachable, check(SKIPPED, notChecked), check(SKIPPED, notChecked));
        }
        int listStatus = list.path("status").asInt();
        reachable = check(OK, server + " answered.");

        // Key: the model list first; when that is refused or missing, the test request decides.
        if (ok(listStatus)) {
            key = check(OK, server + " accepted the API key.");
        } else if (listStatus == 401 || listStatus == 403) {
            key = check(FAILED, server + " did not accept the API key (HTTP " + listStatus + ")" + detail(list) + " Check the key under Settings > AI Assistant.");
        } else {
            key = null;
        }

        if (model == null || model.isBlank()) {
            checked = check(FAILED, "No model is set. Choose a model and save the settings.");
        } else if (tool.isMissingNode()) {
            checked = check(SKIPPED, "Not checked: the API key was not accepted.");
        } else if (tool.has("error")) {
            checked = check(FAILED, "The test request to '" + model + "' failed: " + tool.path("error").asText());
        } else {
            int status = tool.path("status").asInt();
            String body = tool.path("body").asText("").toLowerCase(Locale.ROOT);
            if (ok(status)) {
                if (key == null) {
                    key = check(OK, server + " accepted the API key (the model list was not available: HTTP " + listStatus + ").");
                }
                checked = tool.path("toolCalled").asBoolean()
                        ? check(OK, "'" + model + "' answered and called the test tool, so it supports tool calling." + listed(list, model))
                        : check(FAILED, "'" + model + "' answered but did not call the test tool, so it probably does not support tool calling. "
                                + "The AI Assistant needs tool calling: choose another model.");
            } else if (status == 401 || status == 403) {
                key = check(FAILED, server + " did not accept the API key (HTTP " + status + ")" + detail(tool) + " Check the key under Settings > AI Assistant.");
                checked = check(SKIPPED, "Not checked: the API key was not accepted.");
            } else if (billing(status, body)) {
                key = check(FAILED, "The API key was accepted, but the account has no credit or quota left (HTTP " + status + ")" + detail(tool)
                        + " Check the billing in the provider's console.");
                checked = check(SKIPPED, "Not checked: the account has no credit or quota left.");
            } else if (status == 404 || body.contains("model_not_found") || body.contains("not_found_error")) {
                checked = check(FAILED, "The model '" + model + "' was not found at " + server + " (HTTP " + status + ")" + detail(tool) + available(list));
            } else if (status == 400 && body.contains("tool")) {
                checked = check(FAILED, "'" + model + "' does not support tool calling, which the AI Assistant needs (HTTP 400)" + detail(tool) + " Choose another model.");
            } else if (status == 429 || status == 529 || status == 503) {
                checked = check(WARNING, server + " is busy or rate limited (HTTP " + status + ")" + detail(tool) + " Try again in a minute.");
            } else {
                checked = check(FAILED, "The test request to '" + model + "' failed (HTTP " + status + ")" + detail(tool));
            }
        }
        if (key == null) {
            key = check(WARNING, "Not confirmed: the model list was not available (HTTP " + listStatus + ")" + detail(list)
                    + (openAi ? " Check the API base URL: it usually ends in /v1." : ""));
        }
        return build(reachable, key, checked);
    }

    /** One line per check, for the server log. */
    public static String summary(JsonNode report) {
        StringBuilder out = new StringBuilder();
        for (JsonNode c : report.path("checks")) {
            out.append(out.length() == 0 ? "" : "; ").append(c.path("label").asText()).append(' ').append(c.path("status").asText()).append(": ").append(c.path("message").asText());
        }
        return out.toString();
    }

    private static boolean ok(int status) {
        return status >= 200 && status < 300;
    }

    private static boolean billing(int status, String body) {
        return status == 402 || body.contains("credit balance") || body.contains("insufficient_quota") || body.contains("billing")
                || (status == 429 && body.contains("quota"));
    }

    private static String listed(JsonNode list, String model) {
        if (!list.path("models").isArray() || list.path("models").size() == 0) {
            return "";
        }
        for (JsonNode id : list.path("models")) {
            if (model.equals(id.asText())) {
                return "";
            }
        }
        return " (It is not in the server's model list, but it answered.)";
    }

    private static String available(JsonNode list) {
        int n = list.path("models").size();
        return n > 0 ? " The server lists " + n + " models: use Refresh list and choose one." : "";
    }

    /** ": message." from the error body (the provider's error message where the JSON has one), or ".". */
    static String detail(JsonNode probe) {
        String body = probe.path("body").asText("").trim();
        if (body.isEmpty()) {
            return ".";
        }
        try {
            JsonNode json = MAPPER.readTree(body);
            JsonNode error = json.path("error");
            String message = error.isTextual() ? error.asText() : error.path("message").asText(json.path("message").asText(""));
            if (!message.isBlank()) {
                body = message.trim();
            }
        } catch (Exception e) {
            // Not JSON: use the text as it is.
        }
        body = body.replaceAll("\\s+", " ");
        if (body.length() > MAX_DETAIL) {
            body = body.substring(0, MAX_DETAIL) + "…";
        }
        return ": " + body + (body.endsWith(".") ? "" : ".");
    }

    private static String[] check(String status, String message) {
        return new String[] { status, message };
    }

    private static ObjectNode build(String[] reachable, String[] key, String[] model) {
        ObjectNode out = MAPPER.createObjectNode();
        ArrayNode checks = out.putArray("checks");
        add(checks, "reachable", "Reachable", reachable);
        add(checks, "key", "Key accepted", key);
        add(checks, "model", "Model", model);
        out.put("ok", OK.equals(reachable[0]) && OK.equals(key[0]) && OK.equals(model[0]));
        return out;
    }

    private static void add(ArrayNode checks, String id, String label, String[] check) {
        checks.addObject().put("id", id).put("label", label).put("status", check[0]).put("message", check[1]);
    }
}
