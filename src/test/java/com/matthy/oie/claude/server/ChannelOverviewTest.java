/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.util.DonkeyElement;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.Filter;
import com.mirth.connect.model.Rule;
import com.mirth.connect.model.Step;
import com.mirth.connect.model.Transformer;

public class ChannelOverviewTest {

    /** Connector settings with a fixed formatted form, or none (null) like OIE's receivers. */
    private static final class TestProperties extends ConnectorProperties {
        private final String formatted;

        TestProperties(String formatted) {
            this.formatted = formatted;
        }

        @Override
        public String getProtocol() {
            return "TEST";
        }

        @Override
        public String getName() {
            return "Test";
        }

        @Override
        public String toFormattedString() {
            return formatted;
        }

        @Override
        public boolean equals(Object o) {
            return o == this;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public Map<String, Object> getPurgedProperties() {
            return Collections.emptyMap();
        }

        @Override
        public void migrate3_0_1(DonkeyElement element) {}

        @Override
        public void migrate3_0_2(DonkeyElement element) {}

        @Override
        public void migrate3_1_0(DonkeyElement element) {}

        @Override
        public void migrate3_2_0(DonkeyElement element) {}

        @Override
        public void migrate3_3_0(DonkeyElement element) {}

        @Override
        public void migrate3_4_0(DonkeyElement element) {}

        @Override
        public void migrate3_5_0(DonkeyElement element) {}

        @Override
        public void migrate3_6_0(DonkeyElement element) {}

        @Override
        public void migrate3_7_0(DonkeyElement element) {}

        @Override
        public void migrate3_9_0(DonkeyElement element) {}

        @Override
        public void migrate3_11_0(DonkeyElement element) {}

        @Override
        public void migrate3_11_1(DonkeyElement element) {}

        @Override
        public void migrate3_12_0(DonkeyElement element) {}
    }

    private static ConnectorProperties properties(String formatted) {
        return new TestProperties(formatted);
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

    private static Connector connector(int metaDataId, String name, String transport, ConnectorProperties props, Step... steps) {
        Connector c = new Connector(name);
        c.setMetaDataId(metaDataId);
        c.setTransportName(transport);
        c.setProperties(props);
        c.setEnabled(true);
        c.setFilter(new Filter());
        Transformer t = new Transformer();
        t.setInboundDataType("HL7V2");
        t.setOutboundDataType("XML");
        t.setElements(Arrays.asList(steps));
        c.setTransformer(t);
        return c;
    }

    private static Channel channel() {
        Channel channel = new Channel();
        channel.setId("1111-2222");
        channel.setName("ADT in");
        channel.setDescription("Receives ADT from the HIS.");
        channel.setDeployScript("// This script executes once when the channel is deployed\n// You only have access to the globalMap and globalChannelMap here to persist data\nreturn;");
        channel.setPreprocessingScript("// Modify the message variable below to pre process data\nreturn message;");
        channel.setPostprocessingScript("logger.info('done');\nreturn;");
        channel.setSourceConnector(connector(0, "Source", "TCP Listener", properties(null), step("patient id", "channelMap.put('pid', msg['PID']['PID.3']['PID.3.1'].toString());", true)));
        channel.addDestination(connector(1, "To archive", "HTTP Sender",
                properties("URL: https://archive.example/api\nUSERNAME: oie\n\n[HEADERS]\nAuthorization: Bearer abc.def\n"), step("old step", "logger.info('x');", false)));
        channel.addDestination(connector(2, "To lab", "Channel Writer", properties("CHANNEL ID: 3333-4444\n")));
        return channel;
    }

    private static String describe(Set<Integer> only) {
        return ChannelOverview.describe(channel(), only, true, List.of("HL7 helpers"), List.of("archiveUrl"), Map.of("3333-4444", "Lab out"),
                o -> "<props><host>h</host><password>p4ss</password><pluginProperties><entry>user:secret</entry></pluginProperties></props>");
    }

    @Test
    public void describesTheChannel() {
        String text = describe(null);
        assertTrue(text, text.startsWith("Channel 'ADT in' (1111-2222, revision 0), enabled\nDescription: Receives ADT from the HIS.\n"));
        assertTrue(text, text.contains("Code template libraries: HL7 helpers\nConfiguration Map keys used: archiveUrl\n"));
        assertTrue(text, text.contains("Deploy: (default, does nothing)\n"));
        assertTrue(text, text.contains("Preprocessor: (default, does nothing)\n"));
        assertTrue(text, text.contains("Postprocessor:\n  logger.info('done');"));
        assertTrue(text, text.contains("== Source: 'Source' (TCP Listener)\nData types: HL7V2 -> XML\n"));
        assertTrue(text, text.contains("channelMap.put('pid', msg['PID']['PID.3']['PID.3.1'].toString());"));
        assertTrue(text, text.contains("== Destination 1: 'To archive' (HTTP Sender), waits for the previous destination\n"));
        assertTrue(text, text.contains("'old step' (disabled)"));
        assertTrue(text, text.contains("CHANNEL ID: 3333-4444 ('Lab out')"));
    }

    @Test
    public void secretsInSettingsAreHidden() {
        String text = describe(null);
        assertTrue(text, text.contains("Authorization: (hidden)"));
        assertTrue(text, text.contains("USERNAME: oie"));
        assertFalse(text, text.contains("abc.def"));
        // The source has no formatted settings: its XML, credentials emptied and plugin properties left out.
        assertTrue(text, text.contains("<password></password>"));
        assertFalse(text, text.contains("p4ss"));
        assertFalse(text, text.contains("user:secret"));
    }

    @Test
    public void onlyTheAskedConnectors() {
        String text = describe(Set.of(2));
        assertFalse(text, text.contains("== Source"));
        assertFalse(text, text.contains("== Destination 1"));
        assertTrue(text, text.contains("== Destination 2"));
        assertTrue(text, text.contains("(Connectors not included: [0, 1]. Ask for them with metaDataIds.)"));
    }

    @Test
    public void hidesPasswordsInUrlsAndConnectionStrings() {
        assertEquals("jdbc:sqlserver://db1;user=sa;password=(hidden);db=x",
                ChannelOverview.hideSecrets("jdbc:sqlserver://db1;user=sa;password=Geheim1;db=x"));
        assertEquals("sftp://oie:(hidden)@files.example/out", ChannelOverview.hideSecrets("sftp://oie:Geheim1@files.example/out"));
        assertEquals("var x = a ? b : c; // token: kept in scripts", ChannelOverview.hideSecrets("var x = a ? b : c; // token: kept in scripts"));
    }

    @Test
    public void recognisesDefaultScripts() {
        assertTrue(ChannelOverview.isDefaultScript(null));
        assertTrue(ChannelOverview.isDefaultScript("// comment\n/* block */\nreturn;"));
        assertTrue(ChannelOverview.isDefaultScript("return message;"));
        assertFalse(ChannelOverview.isDefaultScript("logger.info('x');\nreturn;"));
    }

    @Test
    public void findsConfigurationMapKeys() {
        String text = "var u = $cfg('archiveUrl'); var p = configurationMap.get(\"labPort\"); var h = $('hisHost'); host=${dbHost}; var x = 'unused';";
        assertEquals(List.of("archiveUrl", "dbHost", "hisHost", "labPort"),
                ChannelOverview.configKeysUsed(text, List.of("labPort", "archiveUrl", "hisHost", "dbHost", "unused", "other")));
    }

    @Test
    public void aRuleIsDescribedWithItsOperator() {
        Rule rule = new Rule() {
            @Override
            public String getScript(boolean loadFiles) {
                return "return true;";
            }

            @Override
            public String getType() {
                return "JavaScript";
            }

            @Override
            public Rule clone() {
                return this;
            }
        };
        rule.setName("always");
        rule.setEnabled(true);
        Channel channel = channel();
        channel.getSourceConnector().getFilter().setElements(List.of(rule));
        String text = ChannelOverview.describe(channel, Set.of(0), null, List.of(), List.of(), Map.of(), o -> "");
        assertTrue(text, text.contains("Filter:\n  0. [JavaScript] 'always'\n  --- filter 0 'always' as the script OIE runs:\n    return true;"));
        assertTrue(text, text.contains("Code template libraries: (none)"));
    }
}
