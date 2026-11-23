/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ExplainCodeMenuTest {

    private static final String CODE = "var pid = msg['PID'];\nchannelMap.put('mrn', pid['PID.3']['PID.3.1'].toString());\n";

    @Test
    public void aSelectionNamesItsLinesAndTheStep() {
        String request = ExplainCodeMenu.request("transformer step #2 'Map PID' of connector 0 'Source'", CODE, true, 4, 5);
        assertTrue(request, request.startsWith("Explain this code (lines 4-5 of the transformer step #2 'Map PID' of connector 0 'Source'): "));
        assertTrue(request, request.endsWith("```javascript\nvar pid = msg['PID'];\nchannelMap.put('mrn', pid['PID.3']['PID.3.1'].toString());\n```"));
        assertTrue("unsaved edits are announced", request.contains("may include unsaved changes"));
    }

    @Test
    public void oneSelectedLineIsNamedAsALine() {
        assertTrue(ExplainCodeMenu.request("deploy script of the channel", "logger.info('x');", true, 7, 7).startsWith("Explain this code (line 7 of the deploy script of the channel): "));
    }

    @Test
    public void unknownLinesStillSayItIsASelection() {
        assertTrue(ExplainCodeMenu.request("code template", "x();", true, 0, 0).startsWith("Explain this code (the selected code of the code template): "));
    }

    @Test
    public void withoutASelectionTheWholeScriptIsExplained() {
        assertTrue(ExplainCodeMenu.request("global preprocessor script", "return message;", false, 0, 0).startsWith("Explain this code (the whole global preprocessor script): "));
    }

    @Test
    public void veryLongCodeIsClipped() {
        StringBuilder code = new StringBuilder();
        while (code.length() < ExplainCodeMenu.MAX_CODE_CHARS + 500) {
            code.append("logger.info('line');\n");
        }
        String request = ExplainCodeMenu.request("filter rule", code.toString(), false, 0, 0);
        int extra = code.toString().strip().length() - ExplainCodeMenu.MAX_CODE_CHARS;
        assertTrue(request, request.endsWith("\n// ... " + extra + " more characters left out\n```"));
    }

    @Test
    public void anEmptyEditorSaysSo() {
        assertTrue(ExplainCodeMenu.request("filter rule", "  \n", false, 0, 0).endsWith("```javascript\n// (empty)\n```"));
        assertEquals(ExplainCodeMenu.request("filter rule", null, false, 0, 0), ExplainCodeMenu.request("filter rule", "", false, 0, 0));
    }
}
