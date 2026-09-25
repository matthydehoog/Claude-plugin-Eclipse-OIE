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
