/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** The compile check with OIE's default language version (es6) and the Rhino OIE ships. */
public class ScriptCheckTest {

    private final ScriptCheck check = new ScriptCheck(() -> ScriptCheck.ES6);

    private List<String> errors(String script) {
        Map<String, String> scripts = new LinkedHashMap<>();
        scripts.put("transformer step 2 'Map PID' of connector 1", script);
        return check.check(scripts);
    }

    @Test
    public void validScriptsPass() {
        // E4X, return outside a function (OIE wraps scripts in one), and the ES6 that Rhino accepts.
        assertEquals(List.of(), errors("var pid = msg['PID'];\n"
                + "channelMap.put('mrn', pid['PID.3']['PID.3.1'].toString());\n"
                + "for each (var obx in msg..OBX) { logger.info(obx['OBX.5'].toString()); }\n"
                + "let count = 0;\nconst max = 10;\n"
                + "var label = `MRN ${count}`;\nvar { a, b } = { a: 1, b: 2 };\nvar inc = (x) => x + 1;\n"
                + "return true;"));
        assertEquals(List.of(), errors("function format(d) { return DateUtil.formatDate('yyyyMMdd', d); }"));
    }

    @Test
    public void optionalChainingIsReportedWithItsLine() {
        List<String> errors = errors("var pid = msg['PID'];\nlogger.info(pid?.toString());\nreturn true;");
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith("transformer step 2 'Map PID' of connector 1, line 2: "));
        assertTrue(errors.get(0), errors.get(0).endsWith("\n    logger.info(pid?.toString());"));
    }

    @Test
    public void commonModernSyntaxFails() {
        for (String script : new String[] {
                "class Patient { constructor(id) { this.id = id; } }",
                "async function load() { await fetch('x'); }",
                "var mrn = msg?.PID;",
                "var x = channelMap.get('a') ?? 'default';",
                "function f(a = 1) { return a; }",
                "var list = [...items];" }) {
            assertEquals(script, 1, errors(script).size());
        }
    }

    @Test
    public void anUnclosedBlockIsReportedAtTheEnd() {
        List<String> errors = errors("if (true) {\n  logger.info('x');\n");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0), errors.get(0).startsWith("transformer step 2 'Map PID' of connector 1, at the end: "));
    }

    @Test
    public void eachScriptIsCheckedOnItsOwn() {
        Map<String, String> scripts = new LinkedHashMap<>();
        scripts.put("deploy script", "return;");
        scripts.put("preprocessor", "return message?.trim();");
        scripts.put("postprocessor", "var a = ;");
        scripts.put("undeploy script", "   ");
        List<String> errors = check.check(scripts);
        assertEquals(2, errors.size());
        assertTrue(errors.get(0).startsWith("preprocessor, line 1: "));
        assertTrue(errors.get(1).startsWith("postprocessor, line 1: "));
        assertEquals("ES6", check.languageVersion());
    }
}
