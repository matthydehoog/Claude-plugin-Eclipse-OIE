package com.matthy.oie.claude.engine;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.anthropic.core.ObjectMappers;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * After a server-side fallback in the middle of a streamed answer, the message keeps the declined
 * attempt's partial output before a fallback block. Only its text may go back into the history.
 */
public class FallbackTest {

    private static BetaMessage message(String content) throws Exception {
        String json = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-opus-4-8\","
                + "\"content\":" + content + ",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}";
        return ObjectMappers.jsonMapper().readValue(json, BetaMessage.class);
    }

    private static JsonNode json(BetaMessageParam param) throws Exception {
        return new ObjectMapper().readTree(ObjectMappers.jsonMapper().writeValueAsString(param));
    }

    @Test
    public void keepsOnlyTextBeforeTheFallback() throws Exception {
        BetaMessage response = message("["
                + "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"c2ln\"},"
                + "{\"type\":\"text\",\"text\":\"Channel ADT in has \"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_declined\",\"name\":\"oie_get_channel\",\"input\":{}},"
                + "{\"type\":\"fallback\",\"from\":{\"model\":\"claude-opus-5\"},\"to\":{\"model\":\"claude-opus-4-8\"}},"
                + "{\"type\":\"text\",\"text\":\"3 errors.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_ok\",\"name\":\"oie_list_channels\",\"input\":{}}]");

        int start = SdkModelLoop.lastFallback(response.content()) + 1;
        assertEquals(4, start);

        JsonNode content = json(SdkModelLoop.toParam(response, start)).path("content");
        assertEquals(3, content.size());
        assertEquals("Channel ADT in has ", content.get(0).path("text").asText());
        assertEquals("3 errors.", content.get(1).path("text").asText());
        assertEquals("toolu_ok", content.get(2).path("id").asText());
    }

    @Test
    public void withoutFallbackTheMessageIsUnchanged() throws Exception {
        BetaMessage response = message("["
                + "{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"c2ln\"},"
                + "{\"type\":\"text\",\"text\":\"Let me look.\"},"
                + "{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"oie_list_channels\",\"input\":{}}]");

        assertEquals(-1, SdkModelLoop.lastFallback(response.content()));
        assertEquals(json(response.toParam()), json(SdkModelLoop.toParam(response, 0)));
    }
}
