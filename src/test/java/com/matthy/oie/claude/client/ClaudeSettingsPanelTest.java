/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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

    @Test
    public void billingFollowsTheProvider() {
        assertEquals(ClaudeSettingsPanel.ANTHROPIC_BILLING_URL, ClaudeSettingsPanel.billingUrl(false, ""));
        assertEquals(ClaudeSettingsPanel.OPENAI_BILLING_URL, ClaudeSettingsPanel.billingUrl(true, " https://api.openai.com/v1 "));
        assertEquals(ClaudeSettingsPanel.OPENAI_BILLING_URL, ClaudeSettingsPanel.billingUrl(true, "https://API.openai.com"));
        assertNull("no billing page for a local server", ClaudeSettingsPanel.billingUrl(true, "http://ollama:11434/v1"));
        assertNull(ClaudeSettingsPanel.billingUrl(true, ""));
        assertNull(ClaudeSettingsPanel.billingUrl(true, "not a url"));
        assertNull("no billing page for Mistral", ClaudeSettingsPanel.billingUrl(true, ClaudeSettingsPanel.MISTRAL_BASE_URL));
    }

    @Test
    public void mistralIsTheOpenAiCompatibleProviderAtMistralsBaseUrl() {
        assertEquals(ClaudeSettingsPanel.MISTRAL, ClaudeSettingsPanel.providerChoice("openai-compatible", "https://api.mistral.ai/v1"));
        assertEquals(ClaudeSettingsPanel.MISTRAL, ClaudeSettingsPanel.providerChoice("openai-compatible", " https://API.mistral.ai "));
        assertEquals(ClaudeSettingsPanel.OPENAI_COMPATIBLE, ClaudeSettingsPanel.providerChoice("openai-compatible", "https://api.openai.com/v1"));
        assertEquals(ClaudeSettingsPanel.OPENAI_COMPATIBLE, ClaudeSettingsPanel.providerChoice("openai-compatible", "not a url"));
        assertEquals(ClaudeSettingsPanel.ANTHROPIC, ClaudeSettingsPanel.providerChoice("anthropic", "https://api.mistral.ai/v1"));
        assertEquals(ClaudeSettingsPanel.ANTHROPIC, ClaudeSettingsPanel.providerChoice("", null));

        assertEquals("anthropic", ClaudeSettingsPanel.storedProvider(ClaudeSettingsPanel.ANTHROPIC));
        assertEquals("openai-compatible", ClaudeSettingsPanel.storedProvider(ClaudeSettingsPanel.OPENAI_COMPATIBLE));
        assertEquals("openai-compatible", ClaudeSettingsPanel.storedProvider(ClaudeSettingsPanel.MISTRAL));
    }
}
