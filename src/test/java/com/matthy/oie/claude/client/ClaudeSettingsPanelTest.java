package com.matthy.oie.claude.client;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class ClaudeSettingsPanelTest {

    @Test
    public void tokensShowTheCacheShare() throws Exception {
        String tokens = "{\"scope\":\"pluginKey\",\"input\":1200000,\"cacheRead\":18400000,\"cacheWrite\":300000,\"output\":200000}";

        assertEquals("1.2M input, 18.4M read from the cache (92% of the input), 300.0k written to the cache, 200.0k output (the plugin's API key)",
                ClaudeSettingsPanel.tokens(new ObjectMapper().readTree(tokens)));
        assertEquals("–", ClaudeSettingsPanel.tokens(new ObjectMapper().readTree("{}").path("tokens")));
    }
}
