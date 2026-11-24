/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class UsagesTest {

    private static final String ADT = "Channel 'ADT in' (c-1)";
    private static final String LAB = "Channel 'Lab out' (c-2)";

    private static Usages.Source script(String group, String where, String text) {
        return new Usages.Source(group, where, text, false, true);
    }

    private static Usages.Source settings(String group, String where, String xml) {
        return new Usages.Source(group, where, xml, true, false);
    }

    @Test
    public void aConfigurationMapKeyIsFoundInScriptsAndSettingsWithoutCodeOrValues() {
        List<Usages.Source> sources = List.of(
                script(ADT, "deploy script", "var host = $cfg('dbHost');\nlogger.info('secret-value');"),
                script(ADT, "source 'In', transformer step 0 'Lookup'", "var a = 1;\nvar h = configurationMap.get(\"dbHost\");"),
                settings(ADT, "destination 1 'To DB' (Database Writer)", "<properties>\n  <url>jdbc:postgresql://${dbHost}:5432/x</url>\n  <query>SELECT 1</query>\n</properties>"),
                script(LAB, "deploy script", "var other = $cfg('dbHostname');"),
                script("Global scripts", "global deploy script", "$('dbHost');"));
        String result = new Usages(Usages.CONFIG, "dbHost", null).find(sources);
        assertEquals("Usages of Configuration Map key 'dbHost': 4 locations in 2 places.\n\n"
                + ADT + "\n"
                + "- deploy script, line 1: $cfg\n"
                + "- source 'In', transformer step 0 'Lookup', line 2: configurationMap.get\n"
                + "- destination 1 'To DB' (Database Writer), setting url: ${dbHost}\n\n"
                + "Global scripts\n"
                + "- global deploy script, line 1: $() lookup", result);
        assertFalse("no code or values", result.contains("jdbc") || result.contains("secret") || result.contains("var "));
    }

    @Test
    public void mapVariablesNameTheirMap() {
        List<Usages.Source> sources = List.of(script(ADT, "preprocessor", "globalMap.put('lastMrn', x);\nvar m = $g('lastMrn');\nchannelMap.get('lastMrn');\n$gc('lastMrnX');"));
        String result = new Usages(Usages.MAP, "lastMrn", null).find(sources);
        assertTrue(result, result.contains("- preprocessor, line 1: globalMap\n"));
        assertTrue(result, result.contains("- preprocessor, line 2: $g (globalMap)\n"));
        assertTrue(result, result.endsWith("- preprocessor, line 3: channelMap"));
    }

    @Test
    public void aFunctionHasItsDefinitionAndCallsButNotMethodsOrLongerNames() {
        List<Usages.Source> sources = List.of(
                script("Code template library 'Utils'", "code template 'Dates'", "function formatDate(d) {\n  return DateUtil.formatDate('yyyyMMdd', d);\n}"),
                script(ADT, "source 'In', transformer step 1 'Date'", "var d = formatDate(new Date());\nvar e = myformatDate(1);\nformatDateTime();"));
        String result = new Usages(Usages.FUNCTION, "formatDate", null).find(sources);
        assertTrue(result, result.contains("- code template 'Dates', line 1: definition\n"));
        assertFalse("DateUtil.formatDate is another function", result.contains("line 2: call"));
        assertTrue(result, result.endsWith("- source 'In', transformer step 1 'Date', line 1: call"));
    }

    @Test
    public void aChannelIsFoundByChannelWriterRouterAndId() {
        List<Usages.Source> sources = List.of(
                settings(ADT, "destination 2 'To Lab' (Channel Writer)", "<properties>\n  <channelId>c-2</channelId>\n  <template>${message.encodedData}</template>\n</properties>"),
                script(ADT, "postprocessor", "router.routeMessage('Lab out', msg);\nrouter.routeMessageByChannelId(\"c-2\", m);\nvar note = 'see c-2';"));
        String result = new Usages(Usages.CHANNEL, "Lab out", "c-2").find(sources);
        assertEquals("Usages of channel 'Lab out' (c-2): 4 locations in 1 place.\n\n" + ADT + "\n"
                + "- destination 2 'To Lab' (Channel Writer), setting channelId: Channel Writer\n"
                + "- postprocessor, line 1: router.routeMessage\n"
                + "- postprocessor, line 2: router.routeMessageByChannelId\n"
                + "- postprocessor, line 3: channel ID", result);
    }

    @Test
    public void generatedScriptsGetNoLineNumbers() {
        List<Usages.Source> sources = List.of(new Usages.Source(ADT, "source 'In', transformer step 0 'mrn' [Mapper]", "var mapping;\ntry { mapping = $cfg('facility'); } catch (e) {}", false, false));
        assertTrue(new Usages(Usages.CONFIG, "facility", null).find(sources).endsWith("- source 'In', transformer step 0 'mrn' [Mapper]: $cfg"));
    }

    @Test
    public void aSettingSpanningLinesIsNamedByItsElement() {
        String xml = "<properties>\n  <script>var a = 1;\nvar b = $cfg('key');\n</script>\n  <other>${key}</other>\n</properties>";
        String result = new Usages(Usages.CONFIG, "key", null).find(List.of(settings(ADT, "source 'In' (JavaScript Reader)", xml)));
        assertTrue(result, result.contains("- source 'In' (JavaScript Reader), setting script: $cfg\n"));
        assertTrue(result, result.endsWith("- source 'In' (JavaScript Reader), setting other: ${key}"));
    }

    @Test
    public void nothingFoundAndTheLimitAreSaid() {
        assertEquals("No usages of Configuration Map key 'x' found in the channels, code templates and global scripts (names are case-sensitive).",
                new Usages(Usages.CONFIG, "x", null).find(List.of(script(ADT, "deploy script", "$cfg('X');"))));
        List<Usages.Source> many = new ArrayList<>();
        for (int i = 0; i < Usages.MAX_LOCATIONS + 5; i++) {
            many.add(script("Channel " + i, "deploy script", "$cfg('k');"));
        }
        assertTrue(new Usages(Usages.CONFIG, "k", null).find(many).startsWith("Usages of Configuration Map key 'k': 300 locations in 300 places (first 300 shown; narrow the question)."));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anUnknownKindIsRefused() {
        new Usages("table", "x", null);
    }
}
