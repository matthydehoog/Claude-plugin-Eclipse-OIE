package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import com.mirth.connect.model.Rule;
import com.mirth.connect.model.Step;

public class ChannelEditorTest {

    private static final String MAPPER = "<com.mirth.connect.plugins.mapper.MapperStep>"
            + "<name>patientName</name><sequenceNumber>0</sequenceNumber><enabled>true</enabled>"
            + "<variable>patientName</variable><mapping>msg['PID']['PID.5']['PID.5.1'].toString()</mapping>"
            + "<defaultValue/><replacements/><scope>CHANNEL</scope></com.mirth.connect.plugins.mapper.MapperStep>";

    @Test
    public void elementListAcceptsEveryWrapperAndAddsVersions() throws Exception {
        String expected = "<list><com.mirth.connect.plugins.mapper.MapperStep version=\"4.6.0\">"
                + "<name>patientName</name><sequenceNumber>0</sequenceNumber><enabled>true</enabled>"
                + "<variable>patientName</variable><mapping>msg['PID']['PID.5']['PID.5.1'].toString()</mapping>"
                + "<defaultValue/><replacements/><scope>CHANNEL</scope></com.mirth.connect.plugins.mapper.MapperStep></list>";

        assertEquals(expected, ChannelEditor.elementList("<elements>" + MAPPER + "</elements>", "4.6.0"));
        assertEquals(expected, ChannelEditor.elementList("<list>" + MAPPER + "</list>", "4.6.0"));
        assertEquals(expected, ChannelEditor.elementList(MAPPER, "4.6.0"));
        assertEquals(expected, ChannelEditor.elementList("<?xml version=\"1.0\"?>\n<elements>" + MAPPER + "</elements>", "4.6.0"));
    }

    @Test
    public void elementListKeepsAnExistingVersionAndHandlesEmptyLists() throws Exception {
        String rule = "<com.mirth.connect.plugins.javascriptrule.JavaScriptRule version=\"4.5.2\"><name>x</name><script>return true;</script></com.mirth.connect.plugins.javascriptrule.JavaScriptRule>";

        assertTrue(ChannelEditor.elementList("<elements>" + rule + "</elements>", "4.6.0").contains("version=\"4.5.2\""));
        assertEquals("<list/>", ChannelEditor.elementList("<elements/>", "4.6.0"));
        assertEquals("<list/>", ChannelEditor.elementList("", "4.6.0"));
    }

    @Test
    public void elementListAddsVersionsToNestedObjects() throws Exception {
        String iterator = "<com.mirth.connect.model.IteratorStep><name>each OBX</name><properties class=\"com.mirth.connect.model.IteratorStepProperties\">"
                + "<target>msg['OBX']</target><children>" + MAPPER + "</children></properties></com.mirth.connect.model.IteratorStep>";
        String list = ChannelEditor.elementList(iterator, "4.6.0");

        assertTrue(list.contains("<com.mirth.connect.model.IteratorStep version=\"4.6.0\">"));
        assertTrue(list.contains("<properties class=\"com.mirth.connect.model.IteratorStepProperties\" version=\"4.6.0\">"));
        assertTrue(list.contains("<com.mirth.connect.plugins.mapper.MapperStep version=\"4.6.0\">"));
        assertTrue(list.contains("<target>msg['OBX']</target>")); // plain fields get no version
    }

    @Test
    public void rejectsMalformedXml() throws Exception {
        try {
            ChannelEditor.elementList("<elements><com.mirth.connect.plugins.mapper.MapperStep></elements>", "4.6.0");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().startsWith("The XML is not well-formed"));
        }
    }

    @Test
    public void channelNeedsItsRootAndGetsVersions() throws Exception {
        String channel = "<channel><name>New</name><sourceConnector><name>sourceConnector</name>"
                + "<properties class=\"com.mirth.connect.connectors.vm.VmReceiverProperties\"><sourceConnectorProperties><queueBufferSize>1000</queueBufferSize></sourceConnectorProperties></properties>"
                + "</sourceConnector></channel>";
        String out = ChannelEditor.channel(channel, "4.6.0");

        assertTrue(out.startsWith("<channel version=\"4.6.0\">"));
        assertTrue(out.contains("<sourceConnector version=\"4.6.0\">"));
        assertTrue(out.contains("<properties class=\"com.mirth.connect.connectors.vm.VmReceiverProperties\" version=\"4.6.0\">"));
        assertTrue(out.contains("<sourceConnectorProperties version=\"4.6.0\">"));
        assertTrue(out.contains("<name>New</name>"));

        try {
            ChannelEditor.channel("<connector/>", "4.6.0");
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("<channel>"));
        }
    }

    @Test
    public void clearsCredentials() {
        List<String> cleared = new ArrayList<>();
        String xml = "<properties><host>db</host><username>oie</username><password>***</password><keyStorePassword version=\"x\">secret</keyStorePassword><passwordFile/></properties>";

        assertEquals("<properties><host>db</host><username>oie</username><password></password><keyStorePassword version=\"x\"></keyStorePassword><passwordFile/></properties>",
                ChannelEditor.clearCredentials(xml, cleared));
        assertEquals(Arrays.asList("password", "keyStorePassword"), cleared);
    }

    @Test
    public void describesElementsWithTypeOperatorAndState() {
        List<Rule> rules = Arrays.asList(rule("Is ADT", Rule.Operator.NONE, true), rule("Is ORU", Rule.Operator.OR, false));

        assertEquals("  0. [Test Rule] 'Is ADT'\n  1. OR [Test Rule] 'Is ORU' (disabled)\n", ChannelEditor.describe(rules));
        assertEquals("  (none)\n", ChannelEditor.describe(new ArrayList<Step>()));
    }

    @Test
    public void scriptShowsWhatAnElementDoes() {
        assertEquals("return msg['MSH']['MSH.9']['MSH.9.1'].toString() == 'ADT';", ChannelEditor.script(rule("Is ADT", Rule.Operator.NONE, true)));
        assertFalse(ChannelEditor.script(rule("broken", null, true)).isEmpty());
    }

    private static Rule rule(String name, Rule.Operator operator, boolean enabled) {
        Rule rule = new Rule() {
            @Override
            public String getScript(boolean loadFiles) {
                if (getOperator() == null) {
                    throw new IllegalStateException("no operator");
                }
                return "return msg['MSH']['MSH.9']['MSH.9.1'].toString() == 'ADT';";
            }

            @Override
            public String getType() {
                return "Test Rule";
            }

            @Override
            public Rule clone() {
                return this;
            }
        };
        rule.setName(name);
        rule.setOperator(operator);
        rule.setEnabled(enabled);
        return rule;
    }
}
