/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class SuggestionsTest {

    /** The context texts the Swing client (ClaudeClientPlugin) and the web client (plugin.js) send. */
    @Test
    public void everyClientContextHasItsKind() {
        assertEquals(Suggestions.CHANNEL, Suggestions.kind("Channel open in the editor: 'ADT in' (c-1, revision 4). The AI Assistant sees the saved version; unsaved changes in the editor are not visible."));
        assertEquals(Suggestions.CHANNEL, Suggestions.kind("Selected channel: 'ADT in' (c-1)."));
        assertEquals(Suggestions.MESSAGE, Suggestions.kind("Selected message in the message browser: channel ID c-1, message ID 42, connector (metaDataId) 1"));
        assertEquals(Suggestions.CODE_TEMPLATE, Suggestions.kind("Selected code template: 'Dates' (t-1) in library 'Utils'. The AI Assistant sees the saved version; unsaved changes in the code template editor are not visible."));
        assertEquals(Suggestions.CODE_TEMPLATE, Suggestions.kind("Code template open in the Code Templates view."));
        assertEquals(Suggestions.GLOBAL_SCRIPTS, Suggestions.kind("Global Scripts (deploy, undeploy, preprocessor and postprocessor). The AI Assistant sees the saved version; unsaved changes in the Global Scripts editor are not visible."));
        assertEquals(Suggestions.DASHBOARD, Suggestions.kind("Selected in the dashboard: 'ADT in' (c-1, STARTED), connector 1 'Lab out' (c-2, STOPPED)"));
    }

    @Test
    public void noContextOrAnUnknownOneHasNoSuggestions() {
        assertNull(Suggestions.kind(null));
        assertTrue(Suggestions.forContext(null, "English").isEmpty());
        assertTrue(Suggestions.forContext("Something else", "English").isEmpty());
    }

    @Test
    public void theFixedResponseLanguageIsUsedAndAutomaticIsEnglish() {
        String channel = "Selected channel: 'ADT in' (c-1).";
        assertEquals("Leg uit wat dit kanaal doet.", Suggestions.forContext(channel, "Dutch").get(0));
        assertEquals("Explain what this channel does.", Suggestions.forContext(channel, "Automatic").get(0));
        assertEquals("Explain what this channel does.", Suggestions.forContext(channel, "Klingon").get(0));
        assertEquals(List.of("Why did this message fail?", "Explain how this message was processed.", "Which destinations received this message?", "Show the structure of this message."),
                Suggestions.forContext("Selected message in the message browser: channel ID c-1, message ID 42", null));
    }

    /** Every language of the Response language setting (ClaudeSettingsPanel.LANGUAGES, without Automatic) has every kind, as many as English. */
    @Test
    public void everyLanguageIsComplete() {
        assertEquals(Set.of("English", "Dutch", "German", "French", "Spanish", "Italian", "Portuguese", "Polish", "Swedish", "Danish", "Norwegian", "Finnish"), Suggestions.TEXTS.keySet());
        Map<String, List<String>> english = Suggestions.TEXTS.get("English");
        for (Map.Entry<String, Map<String, List<String>>> language : Suggestions.TEXTS.entrySet()) {
            for (Map.Entry<String, List<String>> kind : english.entrySet()) {
                List<String> texts = language.getValue().get(kind.getKey());
                assertEquals(language.getKey() + " " + kind.getKey(), kind.getValue().size(), texts.size());
                texts.forEach(t -> assertTrue(language.getKey() + ": " + t, !t.isBlank() && t.length() < 80));
            }
        }
    }
}
