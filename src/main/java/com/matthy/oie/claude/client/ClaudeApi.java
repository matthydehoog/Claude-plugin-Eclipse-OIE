package com.matthy.oie.claude.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.core.ClientException;
import com.mirth.connect.client.ui.PlatformUI;

/** Thin JSON wrapper around the plugin's servlet. All calls block; use them off the event thread. */
final class ClaudeApi {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private ClaudeApi() {}

    private static ClaudeServletInterface servlet() {
        return PlatformUI.MIRTH_FRAME.mirthClient.getServlet(ClaudeServletInterface.class);
    }

    static JsonNode chat(String conversationId, String message, String context) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        if (conversationId != null) {
            body.put("conversationId", conversationId);
        }
        body.put("message", message);
        if (context != null) {
            body.put("context", context);
        }
        return MAPPER.readTree(servlet().chat(body.toString()));
    }

    static JsonNode job(String jobId, int after) throws Exception {
        return MAPPER.readTree(servlet().getJob(jobId, after));
    }

    static JsonNode confirm(String jobId, String actionId, boolean approved) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("actionId", actionId);
        body.put("approved", approved);
        return MAPPER.readTree(servlet().confirm(jobId, body.toString()));
    }

    static void cancel(String jobId) throws Exception {
        servlet().cancel(jobId);
    }

    static JsonNode settings() throws Exception {
        return MAPPER.readTree(servlet().getSettings());
    }

    static JsonNode spend(boolean refresh) throws Exception {
        return MAPPER.readTree(servlet().getSpend(refresh));
    }

    static JsonNode saveSettings(ObjectNode settings) throws Exception {
        return MAPPER.readTree(servlet().setSettings(settings.toString()));
    }

    /** The server puts a readable explanation in the response body; show that rather than the stack. */
    static String message(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && !(cause instanceof ClientException)) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        // Mirth wraps the response body in text like "Method failed: ... <body>"; keep the last line.
        String[] lines = msg.trim().split("\\r?\\n");
        return lines[lines.length - 1];
    }
}
