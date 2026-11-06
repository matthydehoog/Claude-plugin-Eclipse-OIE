/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The model list the settings screens show: Anthropic's models newest first, or the fixed list
 * below when Anthropic cannot be asked. A saved model that is not in the list stays selectable.
 */
final class ModelList {

    /** Used when the Models API cannot be reached or no API key is set yet. */
    static final List<String> FALLBACK = List.of("claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-fable-5-1", "claude-haiku-4-5");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ModelList() {}

    /**
     * @param fetched the array from {@link ModelReader#list}, or null to use the fixed list
     * @param problem why the fixed list is used (shown to the user), or null
     * @param saved the model in the settings
     * @param fetchedAt when the list was read from Anthropic, or 0
     * @return source ("anthropic" or "fallback"), fetchedAt, note, warning and models (id, displayName, createdAt, unlisted)
     */
    static ObjectNode build(JsonNode fetched, String problem, String saved, long fetchedAt) {
        ObjectNode out = MAPPER.createObjectNode();
        Map<String, ObjectNode> models = new LinkedHashMap<>();
        JsonNode fetchedModels = fetched != null && fetched.isObject() ? fetched.path("models") : fetched;
        String provider = fetched != null && fetched.isObject() ? fetched.path("source").asText("openai-compatible") : "anthropic";
        boolean fromProvider = fetchedModels != null && fetchedModels.isArray() && fetchedModels.size() > 0;
        boolean fromAnthropic = fromProvider && "anthropic".equals(provider);
        if (fromProvider) {
            List<JsonNode> sorted = new ArrayList<>();
            fetchedModels.forEach(sorted::add);
            // Newest first; ISO 8601 timestamps in UTC sort as text.
            sorted.sort(Comparator.comparing((JsonNode m) -> m.path("createdAt").asText("")).reversed().thenComparing(m -> m.path("id").asText("")));
            for (JsonNode m : sorted) {
                String id = m.path("id").asText("").trim();
                if (!id.isEmpty() && !models.containsKey(id)) {
                    ObjectNode model = MAPPER.createObjectNode().put("id", id).put("displayName", m.path("displayName").asText(id));
                    if (!m.path("createdAt").asText("").isEmpty()) {
                        model.put("createdAt", m.path("createdAt").asText());
                    }
                    models.put(id, model);
                }
            }
            out.put("source", provider);
            if (fetchedAt > 0) {
                out.put("fetchedAt", Instant.ofEpochMilli(fetchedAt).toString());
            }
        } else {
            for (String id : FALLBACK) {
                models.put(id, MAPPER.createObjectNode().put("id", id).put("displayName", id));
            }
            out.put("source", "fallback");
            out.put("note", problem != null && !problem.isBlank() ? problem : "Anthropic's model list could not be loaded; a fixed list is shown.");
        }

        String current = saved == null ? "" : saved.trim();
        ArrayNode list = out.putArray("models");
        if (!current.isEmpty() && !models.containsKey(current)) {
            list.addObject().put("id", current).put("displayName", current).put("unlisted", true);
            if (fromProvider) {
                out.put("warning", "The saved model " + current + " is not in the configured endpoint's model list. Choose a model supported by that endpoint.");
            }
        }
        models.values().forEach(list::add);
        return out;
    }
}
