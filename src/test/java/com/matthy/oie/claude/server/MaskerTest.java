package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Same cases as mask.test.ts in the oie-mcp-server. */
public class MaskerTest {

    private final Masker masker = new Masker(null);

    @Test
    public void masksRawGdtPatientLinesButKeepsResults() {
        String gdt = "0163101Samplesmith\r\n0113102John\r\n017310301101945\r\n0118420142\r\n";
        String out = masker.mask(gdt);
        assertTrue(out, out.contains("0163101***\r\n"));
        assertTrue(out, out.contains("0113102***\r\n"));
        assertTrue(out, out.contains("0173103***\r\n"));
        assertTrue(out, out.contains("0118420142\r\n"));
    }

    @Test
    public void masksGdtXmlPatientElements() {
        String xml = "<F3101 name=\"Patient name\">Samplesmith</F3101><F3110 name=\"Patient sex\">1</F3110><F8420 name=\"Result value\">142</F8420>";
        assertEquals("<F3101 name=\"Patient name\">***</F3101><F3110 name=\"Patient sex\">1</F3110><F8420 name=\"Result value\">142</F8420>", masker.mask(xml));
    }

    @Test
    public void masksHl7Er7PidFieldsAndLeavesOtherSegments() {
        String hl7 = "MSH|^~\\&|A|B\rPID|1||12345^^^HOSP||Jansen^Piet||19800101|M\rOBX|1|NM|BMI||24";
        String out = masker.mask(hl7);
        assertTrue(out, out.contains("\rPID|1||***||***||***|M\r"));
        assertTrue(out, out.contains("OBX|1|NM|BMI||24"));
        assertTrue(out, out.startsWith("MSH|^~\\&|A|B"));
    }

    @Test
    public void masksHl7Er7NteComments() {
        String hl7 = "MSH|^~\\&|A|B\rNTE|1|L|Pt called, see J.|RE\rNTE|2||ok\rOBX|1|NM|BMI||24";
        String out = masker.mask(hl7);
        assertTrue(out, out.contains("\rNTE|1|L|***\r"));
        assertTrue(out, out.contains("\rNTE|2||***\r"));
        assertTrue(out, out.contains("OBX|1|NM|BMI||24"));
    }

    @Test
    public void masksLongHl7TextButKeepsShortCodes() {
        String pdf = "JVBERi0xLjQKJcfsj6IKNSAwIG9iago8PC9MZW5ndGggNiAwIFI+PgpzdHJlYW0K";
        String hl7 = "MSH|^~\\&|A|B|C|D|20260925101010||ORU^R01|42|P|2.5\r"
                + "OBX|1|ED|PDF^Report^L||^AP^PDF^Base64^" + pdf + "||||||F\r"
                + "OBX|2|TX|12345-6^Glucose^LN||Patient reports dizziness since yesterday morning||||||F\r"
                + "OBX|3|NM|12345-6^Glucose^LN||5.4|mmol/L|||||F";
        String out = masker.mask(hl7);
        assertTrue(out, out.contains("OBX|1|ED|PDF^Report^L||^AP^PDF^Base64^***||||||F\r"));
        assertTrue(out, out.contains("OBX|2|TX|12345-6^Glucose^LN||***||||||F\r"));
        assertTrue(out, out.contains("OBX|3|NM|12345-6^Glucose^LN||5.4|mmol/L|||||F"));
        assertTrue(out, out.startsWith("MSH|^~\\&|A|B|C|D|20260925101010||ORU^R01|42|P|2.5\r"));
    }

    @Test
    public void masksLongHl7XmlTextAndNteComments() {
        String xml = "<OBX><OBX.3><OBX.3.2>Glucose</OBX.3.2></OBX.3><OBX.5><OBX.5.1>Patient reports dizziness since yesterday</OBX.5.1></OBX.5></OBX>"
                + "<NTE><NTE.1>1</NTE.1><NTE.3><NTE.3.1>short</NTE.3.1></NTE.3></NTE>";
        assertEquals("<OBX><OBX.3><OBX.3.2>Glucose</OBX.3.2></OBX.3><OBX.5><OBX.5.1>***</OBX.5.1></OBX.5></OBX>"
                + "<NTE><NTE.1>1</NTE.1><NTE.3>***</NTE.3></NTE>", masker.mask(xml));
    }

    /** oie_get_message returns XStream XML: segment separators become &#xd; and & becomes &amp;. */
    @Test
    public void masksHl7Er7InsideSerializedMessage() {
        String xml = "<content>MSH|^~\\&amp;|HIS|RIJN|OIE|RIJN|20260925||ADT^A08|42|P|2.5&#xd;PID|1||123456^^^HOSP||Jansen^Piet||19800101|M&#xd;"
                + "NTE|1|L|Called patient&#xd;OBX|1|TX|NOTE||Patient reports dizziness since yesterday morning||||||F&#xd;</content>";
        String out = masker.mask(xml);
        assertEquals("<content>MSH|^~\\&amp;|HIS|RIJN|OIE|RIJN|20260925||ADT^A08|42|P|2.5&#xd;PID|1||***||***||***|M&#xd;"
                + "NTE|1|L|***&#xd;OBX|1|TX|NOTE||***||||||F&#xd;</content>", out);
    }

    /** Transformed content is XML serialized inside XML: &lt;PID.5&gt;. */
    @Test
    public void masksHl7XmlInsideSerializedMessage() {
        String xml = "<content>&lt;HL7Message&gt;&lt;PID&gt;&lt;PID.5&gt;&lt;PID.5.1&gt;Jansen&lt;/PID.5.1&gt;&lt;/PID.5&gt;&lt;PID.8&gt;&lt;PID.8.1&gt;M&lt;/PID.8.1&gt;&lt;/PID.8&gt;&lt;/PID&gt;&lt;/HL7Message&gt;</content>";
        assertEquals("<content>&lt;HL7Message&gt;&lt;PID&gt;&lt;PID.5&gt;***&lt;/PID.5&gt;&lt;PID.8&gt;&lt;PID.8.1&gt;M&lt;/PID.8.1&gt;&lt;/PID.8&gt;&lt;/PID&gt;&lt;/HL7Message&gt;</content>", masker.mask(xml));
    }

    @Test
    public void masksCredentialsInConfiguration() {
        String xml = "<host>db01</host><username>oie</username><password>S3cret!</password><passPhrase>x</passPhrase><encryptPassword>false</encryptPassword>";
        assertEquals("<host>db01</host><username>oie</username><password>***</password><passPhrase>***</passPhrase><encryptPassword>***</encryptPassword>", masker.mask(xml));
    }

    @Test
    public void masksWholePersonSegmentsExceptSetId() {
        String hl7 = "MSH|^~\\&|A|B\rNK1|1|Jansen^Marie|MTH|Dorpsstraat 1^^Arnhem|0612345678\rIN1|1|ZK01|4711|Zilveren Kruis\rIN2|ID77\rMRG|OLD123^^^HOSP\rOBX|1|NM|BMI||24";
        String out = masker.mask(hl7);
        assertTrue(out, out.contains("\rNK1|1|***|***|***|***\r"));
        assertTrue(out, out.contains("\rIN1|1|***|***|***\r"));
        assertTrue(out, out.contains("\rIN2|***\r"));
        assertTrue(out, out.contains("\rMRG|***\r"));
        assertTrue(out, out.contains("OBX|1|NM|BMI||24"));
    }

    @Test
    public void masksExtraPidAndPv1Fields() {
        String[] pid = new String[30];
        java.util.Arrays.fill(pid, "");
        pid[0] = "PID";
        pid[1] = "1";
        pid[8] = "M";
        pid[18] = "ACC99";
        pid[20] = "DL12";
        pid[21] = "MID";
        pid[23] = "Nijmegen";
        pid[29] = "20250101";
        String[] pv1 = new String[20];
        java.util.Arrays.fill(pv1, "");
        pv1[0] = "PV1";
        pv1[2] = "I";
        pv1[7] = "1234^Arts^Anna";
        pv1[19] = "V555";
        String out = masker.mask(String.join("|", pid) + "\r" + String.join("|", pv1));
        String[] maskedPid = pid.clone();
        for (int n : new int[] { 18, 20, 21, 23, 29 }) {
            maskedPid[n] = "***";
        }
        String[] maskedPv1 = pv1.clone();
        maskedPv1[7] = "***";
        maskedPv1[19] = "***";
        assertEquals(String.join("|", maskedPid) + "\r" + String.join("|", maskedPv1), out);
    }

    @Test
    public void masksPersonSegmentsInXml() {
        String xml = "<NK1><NK1.1><NK1.1.1>1</NK1.1.1></NK1.1><NK1.2><NK1.2.1>Jansen</NK1.2.1></NK1.2></NK1><PV1><PV1.2><PV1.2.1>I</PV1.2.1></PV1.2><PV1.19><PV1.19.1>V555</PV1.19.1></PV1.19></PV1>";
        assertEquals("<NK1><NK1.1><NK1.1.1>1</NK1.1.1></NK1.1><NK1.2>***</NK1.2></NK1><PV1><PV1.2><PV1.2.1>I</PV1.2.1></PV1.2><PV1.19>***</PV1.19></PV1>", masker.mask(xml));
    }

    @Test
    public void masksHl7XmlPidFields() {
        String xml = "<PID><PID.5><PID.5.1>Jansen</PID.5.1></PID.5><PID.8><PID.8.1>M</PID.8.1></PID.8></PID>";
        assertEquals("<PID><PID.5>***</PID.5><PID.8><PID.8.1>M</PID.8.1></PID.8></PID>", masker.mask(xml));
    }

    @Test
    public void masksValidBsnOnly() {
        assertEquals("bsn *** x", masker.mask("bsn 111222333 x"));
        assertEquals("order 123456789", masker.mask("order 123456789"));
    }

    @Test
    public void appliesExtraPatterns() {
        Masker custom = new Masker("MRN-\\d+;;geheim");
        assertEquals("*** en ***", custom.mask("MRN-4711 en geheim"));
    }
}
