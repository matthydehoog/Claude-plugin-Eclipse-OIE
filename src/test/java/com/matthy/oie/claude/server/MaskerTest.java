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
