package com.matthy.oie.claude.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A saved conversation must come back as the same history the API received. */
public class HistoryTest {

    private static final String HISTORY = "{\"messages\":["
            + "{\"role\":\"user\",\"content\":\"[Server time: 2026-09-28 10:15 CEST]\\n\\nWhich channels have errors?\"},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"c2lnbmF0dXJl\"},"
            + "{\"type\":\"text\",\"text\":\"I will look.\"},"
            + "{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"oie_list_channels\",\"input\":{\"includeConnectors\":false}}]},"
            + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01\",\"content\":\"| Channel | Errors |\",\"is_error\":false}]},"
            + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Channel **ADT in** has 3 errors.\"}]}]}";

    private static SdkModelLoop loop() {
        return new SdkModelLoop("sk-ant-test", "claude-opus-5", "high", 25, "system", Collections.emptyList(), null);
    }

    @Test
    public void historySurvivesSaveAndLoad() throws Exception {
        try (SdkModelLoop loop = loop()) {
            Object history = loop.loadHistory(HISTORY);
            String saved = loop.saveHistory(history);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode expected = mapper.readTree(HISTORY);
            JsonNode actual = mapper.readTree(saved);
            assertEquals(expected, actual);
            // and once more, so a conversation can be continued more than once
            assertEquals(expected, mapper.readTree(loop.saveHistory(loop.loadHistory(saved))));
        }
    }

    @Test
    public void noHistoryYet() throws Exception {
        try (SdkModelLoop loop = loop()) {
            assertNull(loop.saveHistory(null));
        }
    }
}
