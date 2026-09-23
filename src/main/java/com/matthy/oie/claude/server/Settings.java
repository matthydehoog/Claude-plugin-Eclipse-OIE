package com.matthy.oie.claude.server;

import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.commons.encryption.Encryptor;

/** Plugin settings, stored as extension properties. The API key is stored encrypted. */
public class Settings {

    static final String DEFAULT_MODEL = "claude-opus-5";
    static final String DEFAULT_EFFORT = "high";
    static final int DEFAULT_MAX_TOOL_CALLS = 25;

    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_MODEL = "model";
    private static final String KEY_EFFORT = "effort";
    private static final String KEY_MAX_TOOL_CALLS = "maxToolCalls";
    private static final String KEY_MASK_PATTERNS = "maskPatterns";

    final String apiKey;
    final String model;
    final String effort;
    final int maxToolCalls;
    final String maskPatterns;

    Settings(String apiKey, String model, String effort, int maxToolCalls, String maskPatterns) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = blank(model) ? DEFAULT_MODEL : model.trim();
        this.effort = blank(effort) ? DEFAULT_EFFORT : effort.trim().toLowerCase();
        this.maxToolCalls = maxToolCalls > 0 ? maxToolCalls : DEFAULT_MAX_TOOL_CALLS;
        this.maskPatterns = maskPatterns == null ? "" : maskPatterns;
    }

    static Properties defaults() {
        Properties p = new Properties();
        p.setProperty(KEY_API_KEY, "");
        p.setProperty(KEY_MODEL, DEFAULT_MODEL);
        p.setProperty(KEY_EFFORT, DEFAULT_EFFORT);
        p.setProperty(KEY_MAX_TOOL_CALLS, String.valueOf(DEFAULT_MAX_TOOL_CALLS));
        p.setProperty(KEY_MASK_PATTERNS, "");
        return p;
    }

    static Settings fromProperties(Properties p, Encryptor encryptor) {
        String stored = p.getProperty(KEY_API_KEY, "");
        String apiKey = blank(stored) ? "" : encryptor.decrypt(stored);
        return new Settings(apiKey, p.getProperty(KEY_MODEL), p.getProperty(KEY_EFFORT), parseInt(p.getProperty(KEY_MAX_TOOL_CALLS)), p.getProperty(KEY_MASK_PATTERNS));
    }

    Properties toProperties(Encryptor encryptor) {
        Properties p = new Properties();
        p.setProperty(KEY_API_KEY, apiKey.isEmpty() ? "" : encryptor.encrypt(apiKey));
        p.setProperty(KEY_MODEL, model);
        p.setProperty(KEY_EFFORT, effort);
        p.setProperty(KEY_MAX_TOOL_CALLS, String.valueOf(maxToolCalls));
        p.setProperty(KEY_MASK_PATTERNS, maskPatterns);
        return p;
    }

    /** Applies a settings update from the client; an absent or empty apiKey keeps the current key. */
    Settings merge(JsonNode update) {
        String newKey = update.path(KEY_API_KEY).asText("");
        return new Settings(newKey.isBlank() ? apiKey : newKey, update.path(KEY_MODEL).asText(model), update.path(KEY_EFFORT).asText(effort), update.path(KEY_MAX_TOOL_CALLS).asInt(maxToolCalls), update.path(KEY_MASK_PATTERNS).asText(maskPatterns));
    }

    void writeTo(ObjectNode node) {
        node.put("apiKeySet", !apiKey.isEmpty());
        node.put("apiKeyHint", apiKey.length() > 8 ? apiKey.substring(0, 7) + "…" + apiKey.substring(apiKey.length() - 4) : "");
        node.put(KEY_MODEL, model);
        node.put(KEY_EFFORT, effort);
        node.put(KEY_MAX_TOOL_CALLS, maxToolCalls);
        node.put(KEY_MASK_PATTERNS, maskPatterns);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
