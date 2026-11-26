/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Settings settings(String language) {
        return new Settings("sk-ant-api03-key", "", "claude-opus-5", "high", 25, "", language, null);
    }

    private static Settings review(String review) {
        return new Settings("sk-ant-api03-key", "", "claude-opus-5", "high", 25, "", null, review);
    }

    @Test
    public void reviewDefaultsToMessageData() {
        assertEquals("data", review(null).reviewBeforeSending);
        assertEquals("data", review("nonsense").reviewBeforeSending);
        assertEquals("all", review(" ALL ").reviewBeforeSending);
        assertEquals("off", review("off").reviewBeforeSending);
    }

    @Test
    public void keepConversationsDaysIsOffByDefaultAndLimited() throws Exception {
        Settings off = review("data");
        assertEquals(0, off.keepConversationsDays);
        assertEquals(30, off.merge(MAPPER.readTree("{\"keepConversationsDays\":30}")).keepConversationsDays);
        assertEquals(365, off.merge(MAPPER.readTree("{\"keepConversationsDays\":5000}")).keepConversationsDays);
        assertEquals(0, off.merge(MAPPER.readTree("{\"keepConversationsDays\":-3}")).keepConversationsDays);
        Settings month = off.merge(MAPPER.readTree("{\"keepConversationsDays\":30}"));
        // without API keys, so no encryptor is needed to store them
        Settings stored = new Settings("", "", "claude-opus-5", "high", 25, "", null, "data", 30);
        assertEquals(30, month.merge(MAPPER.readTree("{\"effort\":\"low\"}")).keepConversationsDays);
        assertEquals("30", stored.toProperties(null).getProperty("keepConversationsDays"));
        assertEquals(30, Settings.fromProperties(stored.toProperties(null), null).keepConversationsDays);
        assertEquals(0, Settings.fromProperties(Settings.defaults(), null).keepConversationsDays);
    }

    @Test
    public void mergeKeepsReviewUnlessGiven() throws Exception {
        Settings all = review("all");
        assertEquals("all", all.merge(MAPPER.readTree("{\"effort\":\"low\"}")).reviewBeforeSending);
        assertEquals("off", all.merge(MAPPER.readTree("{\"reviewBeforeSending\":\"off\"}")).reviewBeforeSending);
    }

    @Test
    public void languageDefaultsToAutomatic() {
        assertEquals("Automatic", settings(null).responseLanguage);
        assertEquals("Automatic", settings("  ").responseLanguage);
        assertTrue(settings(null).automaticLanguage());
    }

    @Test
    public void automaticLetsClaudeFollowTheUser() {
        String prompt = AssistantService.systemPrompt(settings("Automatic"));
        assertTrue(prompt, prompt.contains("Answer in the user's language"));
        assertFalse(prompt, prompt.contains("{language}"));
    }

    @Test
    public void fixedLanguageIsInTheSystemPrompt() {
        String prompt = AssistantService.systemPrompt(settings("Dutch"));
        assertTrue(prompt, prompt.contains("Always answer in Dutch"));
        assertFalse(prompt, prompt.contains("Answer in the user's language"));
    }

    @Test
    public void mergeKeepsLanguageUnlessGiven() throws Exception {
        Settings dutch = settings("Dutch");
        assertEquals("Dutch", dutch.merge(MAPPER.readTree("{\"effort\":\"low\"}")).responseLanguage);
        assertEquals("English", dutch.merge(MAPPER.readTree("{\"responseLanguage\":\"English\"}")).responseLanguage);
    }

    @Test
    public void anthropicIsTheDefaultProvider() {
        Settings defaults = Settings.fromProperties(Settings.defaults(), null);
        assertEquals("anthropic", defaults.provider);
        assertEquals("", defaults.baseUrl);
        assertFalse(defaults.openAiCompatible());
        assertFalse("Anthropic needs an API key", defaults.configured());
    }

    @Test
    public void openAiProviderAndBaseUrlAreSavedAndMerged() throws Exception {
        // No API keys, so no encryptor is needed to store it.
        Settings openAi = new Settings("", "", "gpt-4.1", "high", 25, "", null, null, 0).merge(MAPPER.readTree("{\"provider\":\"openai-compatible\",\"baseUrl\":\"http://ollama:11434/v1/\"}"));
        assertEquals("openai-compatible", openAi.provider);
        assertEquals("http://ollama:11434/v1", openAi.baseUrl);
        assertEquals("http://ollama:11434/v1", Settings.fromProperties(openAi.toProperties(null), null).baseUrl);
        assertEquals("openai-compatible", Settings.fromProperties(openAi.toProperties(null), null).provider);
        // Another update without these fields keeps them.
        assertEquals("openai-compatible", openAi.merge(MAPPER.readTree("{\"effort\":\"low\"}")).provider);
        assertEquals("anthropic", openAi.merge(MAPPER.readTree("{\"provider\":\"nonsense\"}")).provider);
    }

    @Test
    public void chatCompletionsIsTheDefaultForExistingSettings() {
        java.util.Properties properties = Settings.defaults();
        properties.remove("apiMode");
        properties.setProperty("provider", "openai-compatible");
        properties.setProperty("model", "gpt-6.2-sol");

        assertEquals("chat-completions", Settings.fromProperties(properties, null).apiMode);
        assertEquals("chat-completions", Settings.defaults().getProperty("apiMode"));
    }

    @Test
    public void apiModeIsSavedReturnedAndKeptOnPartialUpdates() throws Exception {
        Settings initial = Settings.fromProperties(Settings.defaults(), null);
        Settings responses = initial.merge(MAPPER.readTree("{\"provider\":\"openai-compatible\",\"apiMode\":\"responses\"}"));
        Settings stored = Settings.fromProperties(responses.toProperties(null), null);
        com.fasterxml.jackson.databind.node.ObjectNode json = MAPPER.createObjectNode();

        stored.writeTo(json);

        assertEquals("responses", responses.apiMode);
        assertEquals("responses", stored.apiMode);
        assertEquals("responses", json.path("apiMode").asText());
        assertEquals("responses", stored.merge(MAPPER.readTree("{\"model\":\"custom-model\"}")).apiMode);
        assertEquals("responses", stored.merge(MAPPER.readTree("{\"provider\":\"anthropic\"}")).apiMode);
        assertEquals("chat-completions", stored.merge(MAPPER.readTree("{\"apiMode\":\"chat-completions\"}")).apiMode);
    }

    @Test
    public void apiModeIsLimitedToTheSupportedValues() {
        Settings initial = Settings.fromProperties(Settings.defaults(), null);
        assertEquals("responses", initial.merge(MAPPER.createObjectNode().put("apiMode", " RESPONSES ")).apiMode);
        for (String value : new String[] { "", "unknown", "responses-unsupported", String.join("", java.util.Collections.nCopies(100, "responses")) }) {
            assertEquals("chat-completions", initial.merge(MAPPER.createObjectNode().put("apiMode", value)).apiMode);
        }
    }

    @Test
    public void anOpenAiModelIsNotToldItIsClaude() {
        Settings openAi = new Settings("", "", "gpt-4.1", "high", 25, "", null, null, 0, "openai-compatible", "https://api.openai.com/v1");
        String prompt = AssistantService.systemPrompt(openAi);
        assertTrue(prompt, prompt.contains("running as the model gpt-4.1 through an OpenAI-compatible API at api.openai.com"));
        assertTrue(prompt, prompt.contains("you are not Claude"));
        assertFalse(prompt, prompt.contains("You are the Claude assistant"));
        assertFalse(prompt, prompt.contains("{identity}"));
        assertEquals("api.openai.com", AssistantService.modelServer(openAi));

        String claude = AssistantService.systemPrompt(settings("Automatic"));
        assertTrue(claude, claude.startsWith("You are the Claude assistant inside the Administrator"));
        assertEquals("Anthropic", AssistantService.modelServer(settings("Automatic")));
    }

    @Test
    public void anOpenAiProviderNeedsABaseUrlButNoKey() {
        Settings local = new Settings("", "", "qwen3", "high", 25, "", null, null, 0, "openai-compatible", "http://localhost:11434/v1");
        assertTrue(local.configured());
        assertFalse(new Settings("sk-x", "", "gpt", "high", 25, "", null, null, 0, "openai-compatible", " ").configured());
    }
}
