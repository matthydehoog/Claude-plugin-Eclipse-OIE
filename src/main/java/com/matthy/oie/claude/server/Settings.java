package com.matthy.oie.claude.server;

import java.util.Properties;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mirth.commons.encryption.Encryptor;

/** Plugin settings, stored as extension properties. The API keys are stored encrypted. */
public class Settings {

    static final String DEFAULT_MODEL = "claude-opus-5";
    static final String DEFAULT_EFFORT = "high";
    static final int DEFAULT_MAX_TOOL_CALLS = 25;

    private static final String KEY_API_KEY = "apiKey";
    private static final String KEY_ADMIN_API_KEY = "adminApiKey";
    private static final String KEY_MODEL = "model";
    private static final String KEY_EFFORT = "effort";
    private static final String KEY_MAX_TOOL_CALLS = "maxToolCalls";
    private static final String KEY_MASK_PATTERNS = "maskPatterns";

    final String apiKey;
    /** Optional Admin API key, only used to read the organization's cost report. */
    final String adminApiKey;
    final String model;
    final String effort;
    final int maxToolCalls;
    final String maskPatterns;

    Settings(String apiKey, String adminApiKey, String model, String effort, int maxToolCalls, String maskPatterns) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.adminApiKey = adminApiKey == null ? "" : adminApiKey.trim();
        this.model = blank(model) ? DEFAULT_MODEL : model.trim();
        this.effort = blank(effort) ? DEFAULT_EFFORT : effort.trim().toLowerCase();
        this.maxToolCalls = maxToolCalls > 0 ? maxToolCalls : DEFAULT_MAX_TOOL_CALLS;
        this.maskPatterns = maskPatterns == null ? "" : maskPatterns;
    }

    static Properties defaults() {
        Properties p = new Properties();
        p.setProperty(KEY_API_KEY, "");
        p.setProperty(KEY_ADMIN_API_KEY, "");
        p.setProperty(KEY_MODEL, DEFAULT_MODEL);
        p.setProperty(KEY_EFFORT, DEFAULT_EFFORT);
        p.setProperty(KEY_MAX_TOOL_CALLS, String.valueOf(DEFAULT_MAX_TOOL_CALLS));
        p.setProperty(KEY_MASK_PATTERNS, "");
        return p;
    }

    static Settings fromProperties(Properties p, Encryptor encryptor) {
        return new Settings(decrypt(p.getProperty(KEY_API_KEY, ""), encryptor), decrypt(p.getProperty(KEY_ADMIN_API_KEY, ""), encryptor), p.getProperty(KEY_MODEL), p.getProperty(KEY_EFFORT), parseInt(p.getProperty(KEY_MAX_TOOL_CALLS)), p.getProperty(KEY_MASK_PATTERNS));
    }

    Properties toProperties(Encryptor encryptor) {
        Properties p = new Properties();
        p.setProperty(KEY_API_KEY, apiKey.isEmpty() ? "" : encryptor.encrypt(apiKey));
        p.setProperty(KEY_ADMIN_API_KEY, adminApiKey.isEmpty() ? "" : encryptor.encrypt(adminApiKey));
        p.setProperty(KEY_MODEL, model);
        p.setProperty(KEY_EFFORT, effort);
        p.setProperty(KEY_MAX_TOOL_CALLS, String.valueOf(maxToolCalls));
        p.setProperty(KEY_MASK_PATTERNS, maskPatterns);
        return p;
    }

    /**
     * Applies a settings update from the client. An absent or empty key keeps the current one;
     * clearAdminApiKey removes the Admin API key.
     */
    Settings merge(JsonNode update) {
        String newKey = update.path(KEY_API_KEY).asText("");
        String newAdminKey = update.path(KEY_ADMIN_API_KEY).asText("");
        String admin = update.path("clearAdminApiKey").asBoolean(false) ? "" : newAdminKey.isBlank() ? adminApiKey : newAdminKey;
        return new Settings(newKey.isBlank() ? apiKey : newKey, admin, update.path(KEY_MODEL).asText(model), update.path(KEY_EFFORT).asText(effort), update.path(KEY_MAX_TOOL_CALLS).asInt(maxToolCalls), update.path(KEY_MASK_PATTERNS).asText(maskPatterns));
    }

    void writeTo(ObjectNode node) {
        node.put("apiKeySet", !apiKey.isEmpty());
        node.put("apiKeyHint", hint(apiKey));
        node.put("adminApiKeySet", !adminApiKey.isEmpty());
        node.put("adminApiKeyHint", hint(adminApiKey));
        node.put(KEY_MODEL, model);
        node.put(KEY_EFFORT, effort);
        node.put(KEY_MAX_TOOL_CALLS, maxToolCalls);
        node.put(KEY_MASK_PATTERNS, maskPatterns);
    }

    private static String hint(String key) {
        return key.length() > 8 ? key.substring(0, 7) + "…" + key.substring(key.length() - 4) : "";
    }

    private static String decrypt(String stored, Encryptor encryptor) {
        return blank(stored) ? "" : encryptor.decrypt(stored);
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
