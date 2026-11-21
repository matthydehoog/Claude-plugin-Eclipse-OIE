/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class ModelListTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** As SdkModelReader returns it: in the API's order, not necessarily newest first. */
    private static JsonNode fetched() throws Exception {
        return MAPPER.readTree("["
                + "{\"id\":\"claude-haiku-4-5\",\"displayName\":\"Claude Haiku 4.5\",\"createdAt\":\"2025-10-01T00:00:00Z\"},"
                + "{\"id\":\"claude-opus-5-5\",\"displayName\":\"Claude Opus 5.5\",\"createdAt\":\"2026-08-01T00:00:00Z\"},"
                + "{\"id\":\"claude-sonnet-5\",\"displayName\":\"Claude Sonnet 5\",\"createdAt\":\"2026-03-01T00:00:00Z\"},"
                + "{\"id\":\"claude-opus-5-5\",\"displayName\":\"Claude Opus 5.5\",\"createdAt\":\"2026-08-01T00:00:00Z\"}"
                + "]");
    }

    private static List<String> ids(ObjectNode list) {
        List<String> ids = new ArrayList<>();
        list.path("models").forEach(m -> ids.add(m.path("id").asText()));
        return ids;
    }

    @Test
    public void anthropicsModelsComeNewestFirstWithoutDuplicates() throws Exception {
        ObjectNode list = ModelList.build(fetched(), null, "claude-sonnet-5", 1_000L);
        assertEquals("anthropic", list.path("source").asText());
        assertEquals(List.of("claude-opus-5-5", "claude-sonnet-5", "claude-haiku-4-5"), ids(list));
        assertEquals("Claude Opus 5.5", list.path("models").get(0).path("displayName").asText());
        assertEquals("1970-01-01T00:00:01Z", list.path("fetchedAt").asText());
        assertFalse(list.has("warning"));
        assertFalse(list.has("note"));
    }

    @Test
    public void aSavedModelThatIsNoLongerListedStaysWithAWarning() throws Exception {
        ObjectNode list = ModelList.build(fetched(), null, "claude-opus-5", 1_000L);
        assertEquals(List.of("claude-opus-5", "claude-opus-5-5", "claude-sonnet-5", "claude-haiku-4-5"), ids(list));
        assertTrue(list.path("models").get(0).path("unlisted").asBoolean());
        assertTrue(list.path("warning").asText(), list.path("warning").asText().contains("claude-opus-5 is not in Anthropic's model list"));
    }

    @Test
    public void withoutAnthropicTheFixedListIsShownWithTheReason() {
        ObjectNode list = ModelList.build(null, "Cannot reach the Anthropic API from the OIE server (UnknownHostException).", "claude-opus-5", 0);
        assertEquals("fallback", list.path("source").asText());
        assertEquals(ModelList.FALLBACK, ids(list));
        assertTrue(list.path("note").asText().startsWith("Cannot reach the Anthropic API"));
        assertFalse(list.has("fetchedAt"));
        assertFalse("the fixed list cannot tell that a model is retired", list.has("warning"));
    }

    @Test
    public void aSavedModelOutsideTheFixedListIsKeptWithoutAWarning() {
        ObjectNode list = ModelList.build(null, null, "claude-sonnet-6", 0);
        assertEquals("claude-sonnet-6", ids(list).get(0));
        assertTrue(list.path("models").get(0).path("unlisted").asBoolean());
        assertFalse(list.has("warning"));
        assertTrue(list.path("note").asText().contains("fixed list"));
    }

    @Test
    public void anEmptyAnswerFallsBackToTheFixedList() throws Exception {
        ObjectNode list = ModelList.build(MAPPER.readTree("[]"), null, "", 1_000L);
        assertEquals("fallback", list.path("source").asText());
        assertEquals(ModelList.FALLBACK, ids(list));
    }

    @Test
    public void theDefaultModelIsInTheFixedList() {
        assertTrue(ModelList.FALLBACK.contains(Settings.DEFAULT_MODEL));
    }

    @Test
    public void withoutAListAnOpenAiProviderShowsNoClaudeModels() {
        ObjectNode list = ModelList.build(null, "Set the API base URL to load the model list.", "gpt-4.1", 0, true);
        assertEquals("fallback", list.path("source").asText());
        assertEquals(List.of("gpt-4.1"), ids(list));
        assertEquals("Set the API base URL to load the model list.", list.path("note").asText());
    }

    @Test
    public void aSavedClaudeModelIsFlaggedInAnOpenAiList() throws Exception {
        JsonNode fetched = new ObjectMapper().readTree("[{\"id\":\"gpt-4.1\",\"displayName\":\"gpt-4.1\"}]");
        ObjectNode list = ModelList.build(fetched, null, "claude-opus-5", 1_000L, true);
        assertEquals("openai-compatible", list.path("source").asText());
        assertEquals(List.of("claude-opus-5", "gpt-4.1"), ids(list));
        assertTrue(list.path("warning").asText().contains("not in the model server's list"));
    }
}
