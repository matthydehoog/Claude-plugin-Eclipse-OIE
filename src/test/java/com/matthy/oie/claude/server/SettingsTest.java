package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class SettingsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Settings settings(String language) {
        return new Settings("sk-ant-api03-key", "", "claude-opus-5", "high", 25, "", language);
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
}
