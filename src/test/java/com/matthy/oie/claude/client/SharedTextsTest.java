/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** The Swing client and the web administrator send the same requests for the same actions. */
public class SharedTextsTest {

    /** The string value of a JavaScript constant made of "..." literals joined with +. */
    private static String jsConstant(String name) throws Exception {
        String js = new String(Files.readAllBytes(Paths.get("webadmin/web/plugin.js")), StandardCharsets.UTF_8);
        Matcher declaration = Pattern.compile("const " + name + " = ([\\s\\S]*?);\\r?\\n").matcher(js);
        assertTrue(name + " is declared in plugin.js", declaration.find());
        StringBuilder value = new StringBuilder();
        Matcher literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(declaration.group(1));
        while (literal.find()) {
            value.append(literal.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
        }
        return value.toString();
    }

    @Test
    public void explainErrorRequestIsTheSameInSwingAndWeb() throws Exception {
        assertEquals(ClaudeClientPlugin.EXPLAIN_ERROR_SUGGESTION, jsConstant("EXPLAIN_ERROR_SUGGESTION"));
    }

    @Test
    public void queuesRequestIsTheSameInSwingAndWeb() throws Exception {
        assertEquals(ClaudeClientPlugin.QUEUES_SUGGESTION, jsConstant("QUEUES_SUGGESTION"));
    }

    @Test
    public void descriptionRequestIsTheSameInSwingAndWeb() throws Exception {
        assertEquals(ClaudeClientPlugin.DESCRIPTION_SUGGESTION, jsConstant("DESCRIPTION_SUGGESTION"));
    }

    @Test
    public void explainCodeRequestIsTheSameInSwingAndWeb() throws Exception {
        assertEquals(ExplainCodeMenu.EXPLAIN_CODE_REQUEST, jsConstant("EXPLAIN_CODE_REQUEST"));
    }

    @Test
    public void explainErrorAsksForTheFourHeadings() {
        for (String heading : new String[] { "Root cause", "Where", "Which data", "Fix" }) {
            assertTrue(heading, ClaudeClientPlugin.EXPLAIN_ERROR_SUGGESTION.contains(heading + " ("));
        }
    }
}
