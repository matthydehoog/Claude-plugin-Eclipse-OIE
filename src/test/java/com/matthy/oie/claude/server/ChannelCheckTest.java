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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.Test;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.donkey.util.DonkeyElement;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Filter;
import com.mirth.connect.model.MessageStorageMode;
import com.mirth.connect.model.Rule;
import com.mirth.connect.model.Step;
import com.mirth.connect.model.Transformer;

public class ChannelCheckTest {

    /** Connector settings with fixed XML, as a destination with a queue setting. */
    private static final class TestProperties extends ConnectorProperties implements DestinationConnectorPropertiesInterface {
        final String xml;
        final DestinationConnectorProperties destination = new DestinationConnectorProperties();

        TestProperties(String xml, boolean queue, int retries) {
            this.xml = xml;
            destination.setQueueEnabled(queue);
            destination.setRetryCount(retries);
        }

        @Override
        public DestinationConnectorProperties getDestinationConnectorProperties() {
            return destination;
        }

        @Override
        public boolean canValidateResponse() {
            return false;
        }

        @Override
        public ConnectorProperties clone() {
            return this;
        }

        @Override
        public String getProtocol() {
            return "test";
        }

        @Override
        public String getName() {
            return "test";
        }

        @Override
        public String toFormattedString() {
            return "";
        }

        @Override
        public boolean equals(Object o) {
            return o == this;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public void migrate3_0_1(DonkeyElement element) {}

        @Override
        public void migrate3_0_2(DonkeyElement element) {}
    }

    private static Step step(String name, String script, boolean enabled) {
        Step step = new Step() {
            @Override
            public String getScript(boolean loadFiles) {
                return script;
            }

            @Override
            public String getType() {
                return "JavaScript";
            }

            @Override
            public Step clone() {
                return this;
            }
        };
        step.setName(name);
        step.setEnabled(enabled);
        return step;
    }

    private static Rule rule(String name, boolean enabled) {
        Rule rule = new Rule() {
            @Override
            public String getScript(boolean loadFiles) {
                return "return true;";
            }

            @Override
            public String getType() {
                return "Rule Builder";
            }

            @Override
            public Rule clone() {
                return this;
            }
        };
        rule.setName(name);
        rule.setEnabled(enabled);
        return rule;
    }

    private static Connector connector(int metaDataId, String name, String transport, ConnectorProperties props, List<Rule> rules, Step... steps) {
        Connector c = new Connector(name);
        c.setMetaDataId(metaDataId);
        c.setTransportName(transport);
        c.setProperties(props);
        c.setEnabled(true);
        Filter f = new Filter();
        f.setElements(new ArrayList<>(rules));
        c.setFilter(f);
        Transformer t = new Transformer();
        t.setElements(new ArrayList<>(Arrays.asList(steps)));
        c.setTransformer(t);
        c.setResponseTransformer(new Transformer());
        return c;
    }

    /** A clean channel: every check passes. */
    private static Channel clean() {
        Channel channel = new Channel();
        channel.setId("c-1");
        channel.setName("ADT in");
        channel.getProperties().setMessageStorageMode(MessageStorageMode.PRODUCTION);
        channel.setDeployScript("return;");
        channel.setPreprocessingScript("return message;");
        channel.setPostprocessingScript("return;");
        channel.setSourceConnector(connector(0, "Source", "TCP Listener", new TestProperties("<props><port>6661</port></props>", false, 0), List.of(rule("Is ADT", true)),
                step("mrn", "var mrn = msg['PID']['PID.3']['PID.3.1'].toString();\nlogger.error('only on error');", true)));
        channel.addDestination(connector(1, "To DB", "Database Writer",
                new TestProperties("<props><url>jdbc:postgresql://${dbHost}/oie</url><username>oie</username><password>${dbPassword}</password></props>", true, 3), List.of()));
        return channel;
    }

    private static List<ChannelCheck.Finding> check(Channel channel, Map<String, String> unlinked) {
        return ChannelCheck.check(channel, 30, 7, unlinked, p -> ((TestProperties) p).xml);
    }

    private static String text(List<ChannelCheck.Finding> findings) {
        return findings.stream().map(String::valueOf).collect(Collectors.joining("\n"));
    }

    @Test
    public void aCleanChannelHasNoFindings() {
        assertEquals("", text(check(clean(), Map.of())));
    }

    @Test
    public void disabledDestinationsRulesAndStepsAreFound() {
        Channel channel = clean();
        channel.getDestinationConnectors().get(0).setEnabled(false);
        channel.getSourceConnector().getFilter().getElements().get(0).setEnabled(false);
        channel.getSourceConnector().getTransformer().getElements().add(step("old", "logger.info(msg);", false));
        assertEquals("[disabled] source 'Source' (TCP Listener), filter rule 0 'Is ADT': is disabled; check that this is intended\n"
                + "[disabled] source 'Source' (TCP Listener), transformer step 1 'old': is disabled; check that this is intended\n"
                + "[disabled] destination 1 'To DB' (Database Writer): the destination is disabled; messages are not sent to it",
                text(check(channel, Map.of())));
    }

    @Test
    public void credentialsInScriptsAreFoundByLocationWithoutTheirValue() {
        Channel channel = clean();
        channel.setDeployScript("var user = 'oie';\nvar password = 'S3cret!x';\nreturn;");
        channel.setPostprocessingScript("var c = DatabaseConnectionFactory.createDatabaseConnection(driver, url, 'sa', 'Hunter22');\n"
                + "var conn = 'Server=db;User Id=sa;Password=Hunter22;';\nreturn;");
        channel.getSourceConnector().getTransformer().getElements().add(step("call api", "var h = {'Authorization': 'Bearer eyJhbGciOiJIUzI1NiJ9'};\n"
                + "var u = 'https://bob:pa55word@api.example/x';\nvar ok = $cfg('apiPassword');\nvar pw = getPassword();", true));
        String result = text(check(channel, Map.of()));
        assertEquals("[credential] deploy script, line 2: a password, token or key seems to be typed into the script; keep it in the Configuration Map ($cfg('key')) instead\n"
                + "[credential] postprocessor, lines 1, 2: a password, token or key seems to be typed into the script; keep it in the Configuration Map ($cfg('key')) instead\n"
                + "[credential] source 'Source' (TCP Listener), transformer step 1 'call api', lines 1, 2: a password, token or key seems to be typed into the script; keep it in the Configuration Map ($cfg('key')) instead",
                result);
        for (String secret : List.of("S3cret", "Hunter22", "eyJhbG", "pa55word")) {
            assertFalse(secret, result.contains(secret));
        }
    }

    @Test
    public void credentialsInSettingsAreFoundBySettingWithoutTheirValue() {
        Channel channel = clean();
        channel.addDestination(connector(2, "To API", "HTTP Sender", new TestProperties("<props>\n<host>https://api.example</host>\n<headers><entry><string>Authorization</string><list><string>Bearer abcdef123456</string></list></entry></headers>\n"
                + "<password>pl41n</password>\n<timeout>30000</timeout>\n<tokenTimeout>60</tokenTimeout>\n<useAuthentication>true</useAuthentication>\n</props>", true, 0), List.of()));
        channel.addDestination(connector(3, "To DB 2", "Database Writer", new TestProperties("<props><url>jdbc:sqlserver://db;user=sa;password=Hunter22</url><password></password></props>", true, 0), List.of()));
        String result = text(check(channel, Map.of()));
        assertEquals("[credential] destination 2 'To API' (HTTP Sender), setting headers or parameters: a credential is typed into the setting; use a Configuration Map key such as ${dbPassword} instead\n"
                + "[credential] destination 2 'To API' (HTTP Sender), setting password: a credential is typed into the setting; use a Configuration Map key such as ${dbPassword} instead\n"
                + "[credential] destination 3 'To DB 2' (Database Writer), setting url: a credential is typed into the setting; use a Configuration Map key such as ${dbPassword} instead", result);
        assertFalse(result.contains("pl41n") || result.contains("abcdef") || result.contains("Hunter22"));
    }

    @Test
    public void callsToFunctionsOfUnlinkedLibrariesAreFound() {
        Channel channel = clean();
        channel.getSourceConnector().getTransformer().getElements().add(step("date", "var d = formatDate(new Date());\nvar e = DateUtil.formatDate('yyyy', d);", true));
        channel.setDeployScript("function localHelper() {}\nlocalHelper();\nreturn;");
        String result = text(check(channel, Map.of("formatDate", "Utils", "localHelper", "Other", "unused", "Utils")));
        assertEquals("[code template] source 'Source' (TCP Listener), transformer step 1 'date', line 1: calls formatDate() from code template library 'Utils', which is not linked to this channel, "
                + "so the call fails with 'formatDate is not defined'; link the library to the channel", result);
    }

    @Test
    public void remoteDestinationsWithoutAQueueAreFound() {
        Channel channel = clean();
        channel.addDestination(connector(2, "To LIS", "TCP Sender", new TestProperties("<props/>", false, 0), List.of()));
        channel.addDestination(connector(3, "To API", "HTTP Sender", new TestProperties("<props/>", false, 2), List.of()));
        channel.addDestination(connector(4, "To lab", "Channel Writer", new TestProperties("<props/>", false, 0), List.of()));
        assertEquals("[no queue] destination 2 'To LIS' (TCP Sender): the queue is off and there are no retries: when the other system is down, messages end in ERROR instead of waiting; consider queueing (On Failure or Always)\n"
                + "[no queue] destination 3 'To API' (HTTP Sender): the queue is off (2 retries): when the other system is down, messages end in ERROR instead of waiting; consider queueing (On Failure or Always)",
                text(check(channel, Map.of())));
    }

    @Test
    public void developmentStorageAndPruningOffAreFound() {
        Channel channel = clean();
        channel.getProperties().setMessageStorageMode(MessageStorageMode.DEVELOPMENT);
        assertEquals("[storage] channel: message storage is Development: every content type and map of every message is stored; use Production or lower outside development\n"
                + "[storage] channel: message pruning is off: messages are kept forever and the database keeps growing; set Prune metadata older than a number of days",
                text(ChannelCheck.check(channel, null, null, Map.of(), p -> ((TestProperties) p).xml)));
        channel.getProperties().setMessageStorageMode(MessageStorageMode.DISABLED);
        assertEquals("nothing is stored, so pruning does not matter", "", text(ChannelCheck.check(channel, null, null, Map.of(), p -> ((TestProperties) p).xml)));
    }

    @Test
    public void loggingInPerMessageScriptsIsFoundButNotInDeployScripts() {
        Channel channel = clean();
        channel.setDeployScript("logger.info('deployed');\nreturn;");
        channel.setPreprocessingScript("logger.debug(message);\nreturn message;");
        channel.getSourceConnector().getTransformer().getElements().add(step("trace", "var a = 1;\nlogger.info('a');\nlogger.trace('b');", true));
        assertEquals("[logging] preprocessor, line 1: logger.debug runs for every message and fills the server log; remove it or use it only while testing\n"
                + "[logging] source 'Source' (TCP Listener), transformer step 1 'trace', lines 2, 3: logger.info, logger.trace runs for every message and fills the server log; remove it or use it only while testing",
                text(check(channel, Map.of())));
    }

    @Test
    public void findingsAreOrderedByImportance() {
        Channel channel = clean();
        channel.getDestinationConnectors().get(0).setEnabled(false);
        channel.setPreprocessingScript("logger.info(message);\nvar token = 'abc123def';\nreturn message;");
        List<String> kinds = check(channel, Map.of()).stream().map(f -> f.kind).collect(Collectors.toList());
        assertEquals(List.of(ChannelCheck.CREDENTIAL, ChannelCheck.LOGGING, ChannelCheck.DISABLED), kinds);
    }

    @Test
    public void theReportListsChannelsWithFindingsAndNamesTheCleanOnes() {
        ChannelCheck.Finding f = new ChannelCheck.Finding(ChannelCheck.STORAGE, "channel", "message pruning is off");
        String report = ChannelCheck.report(List.of(Map.entry("Channel 'A' (a)", List.of(f)), Map.entry("Channel 'B' (b)", List.of()), Map.entry("Channel 'C' (c)", List.of())));
        assertEquals("Checked 3 channels: 1 finding in 1 channel. Credentials are named by location only.\n\n"
                + "Channel 'A' (a)\n- [storage] channel: message pruning is off\n\n"
                + "No findings: Channel 'B' (b), Channel 'C' (c)", report);
        assertTrue(ChannelCheck.report(List.of(Map.entry("Channel 'B' (b)", List.of()))).startsWith("Checked 1 channel: 0 findings."));
    }

    @Test
    public void aHundredChannelsAreCheckedInOneCall() {
        List<Map.Entry<String, List<ChannelCheck.Finding>>> results = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            Channel channel = clean();
            channel.setPreprocessingScript("logger.info(message);\nreturn message;");
            results.add(Map.entry("Channel " + i, check(channel, Map.of())));
        }
        String report = ChannelCheck.report(results);
        assertTrue(report, report.startsWith("Checked 100 channels: 100 findings in 100 channels."));
    }
}
