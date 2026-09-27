package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import org.junit.Test;

/** The examples in src/test/resources/edifact come from the EDIFACT data type; the XML is its output. */
public class EdifactMaskerTest {

    private final Masker masker = new Masker(null);

    private static String resource(String name) throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/test/resources/edifact", name)), StandardCharsets.UTF_8);
    }

    private static void assertNone(String text, String... values) {
        for (String value : values) {
            assertFalse("Still contains " + value, text.contains(value));
        }
    }

    @Test
    public void masksRawMedlabAndKeepsStructureAndResults() throws Exception {
        String medlab = resource("medlab.edi");
        String expected = medlab
                .replace("PID+2003:04:22+V+Test Patient:::::P.+2'", "PID+***+V+***+***'")
                .replace("PAD+Teststraat 9:355::Teststad:1234 CD+0000000000'", "PAD+***+***'")
                .replace("ART+H+5+A. Pietersen:::+Huisartsenpraktijk Test:24::Teststad:1234 EF::+'", "ART+H+5+***+***+'")
                .replace("ARA:1+J. Jansen+000-0000001'", "ARA:1+***+***'")
                .replace("KOP+NAAR+A. Pietersen+++'", "KOP+NAAR+***+++'")
                .replace("IDE:1+J+00000051'", "IDE:1+J+***'");
        assertEquals(expected, EdifactMasker.mask(medlab));
    }

    @Test
    public void masksMedlabThroughTheMasker() throws Exception {
        String masked = masker.mask(resource("medlab.edi"));

        assertNone(masked, "Test Patient", "2003:04:22", "Teststraat", "Pietersen", "Jansen", "+00000051", ">00000051<");
        assertTrue(masked.contains("PID+***+V+***+***'"));
        assertTrue(masked.contains("BEP:1:1:3+0+Quet/BMI+23.1++kg/m2++10+50+QUET'"));
        assertTrue(masked.contains("UNH+06092100000051+MEDLAB:1+"));
    }

    @Test
    public void masksMedlabOnOneLine() {
        // Without line breaks the segments follow each other directly; the results must stay readable.
        String medlab = "UNH+1+MEDLAB:1'PID+2003:04:22+V+Test Patient:::::P.+2'PAD+Teststraat 9:355::Teststad:1234 CD+0612345678'"
                + "SEC:1:1+MEETWAARDEN'BEP:1:1:1+0+gewicht+70.0++kg++0+1000+GEW'UNT+6+1'";

        assertEquals("UNH+1+MEDLAB:1'PID+***+V+***+***'PAD+***+***'SEC:1:1+MEETWAARDEN'BEP:1:1:1+0+gewicht+70.0++kg++0+1000+GEW'UNT+6+1'",
                masker.mask(medlab));
    }

    @Test
    public void masksMedlabXml() throws Exception {
        String masked = masker.mask(resource("medlab.xml"));

        assertNone(masked, "Test Patient", "2003", "Teststraat", "Pietersen", "Jansen", "+00000051", ">00000051<");
        assertTrue(masked.contains("<PID.01>***</PID.01><PID.02><PID.02.1>V</PID.02.1></PID.02><PID.03>***</PID.03>"));
        assertTrue(masked.contains("<ARA><ARA.00><ARA.00.1>1</ARA.00.1></ARA.00><ARA.01>***</ARA.01>"));
        assertTrue(masked.contains("<KOP.01><KOP.01.1>NAAR</KOP.01.1></KOP.01><KOP.02>***</KOP.02>"));
        assertTrue(masked.contains("<BEP.02><BEP.02.1>Quet/BMI</BEP.02.1></BEP.02><BEP.03><BEP.03.1>23.1</BEP.03.1></BEP.03>"));
        assertTrue(masked.contains("<SEC.01><SEC.01.1>MEETWAARDEN</SEC.01.1></SEC.01>"));
    }

    @Test
    public void masksMedlabXmlInsideSerializedToolOutput() throws Exception {
        String content = "<content>" + Masker.escapeXml(resource("medlab.xml")) + "</content>";
        String masked = masker.mask(content);

        assertNone(masked, "Test Patient", "Teststraat", "Pietersen", "Jansen");
        assertTrue(masked.contains("&lt;PID.03&gt;***&lt;/PID.03&gt;"));
        assertTrue(masked.contains("&lt;BEP.02.1&gt;Quet/BMI&lt;/BEP.02.1&gt;"));
    }

    @Test
    public void masksRawMedspe() throws Exception {
        String masked = masker.mask(resource("medspe.edi"));

        assertNone(masked, "Test:T", "010100KA00", "19000101", "TESTSTR", "Capelle", "Huisarts", "geb. datum", "Chirurg", "Straatnaam");
        assertTrue(masked.contains("PID+PAT+***+***'"));
        assertTrue(masked.contains("DTM+BTH:***:***'"));
        assertTrue(masked.contains("NAD+PAT++++***+***++***+***'"));
        assertTrue(masked.contains("FTX+GMR+1++***'"));
        // other dates stay
        assertTrue(masked.contains("DTM+137:200703260000:203'"));
    }

    @Test
    public void masksMedspeXml() throws Exception {
        String masked = masker.mask(resource("medspe.xml"));

        assertNone(masked, "Test</", "010100KA00", "19000101", "TESTSTR", "Huisarts", "geb. datum");
        assertTrue(masked.contains("<DTM.01><DTM.01.1>BTH</DTM.01.1><DTM.01.2>***</DTM.01.2>"));
        assertTrue(masked.contains("<FTX.04>***</FTX.04>"));
        assertTrue(masked.contains("<PID.01><PID.01.1>PAT</PID.01.1></PID.01><PID.02>***</PID.02>"));
    }

    @Test
    public void masksCutOffXmlAndPastedSegments() throws Exception {
        String medlab = resource("medlab.xml");
        String cutOff = medlab.substring(0, medlab.indexOf("<SEC>"));
        assertNone(masker.mask(cutOff), "Test Patient", "Teststraat", "Pietersen", "Jansen");

        // A fragment without the root: masked with the strictest rules.
        String fragment = "<PID><PID.01><PID.01.1>2003</PID.01.1></PID.01><PID.02><PID.02.1>V</PID.02.1></PID.02>"
                + "<PID.03><PID.03.1>Test Patient</PID.03.1></PID.03></PID>";
        assertEquals("<PID><PID.01>***</PID.01><PID.02>***</PID.02><PID.03>***</PID.03></PID>", masker.mask(fragment));
    }

    @Test
    public void leavesTradeMessagesAlone() throws Exception {
        String orders = resource("orders.edi");
        String ordersXml = resource("orders.xml");

        assertEquals(orders, EdifactMasker.mask(orders));
        assertEquals(ordersXml, EdifactMasker.mask(ordersXml));
    }

    @Test
    public void releasedDelimiterDoesNotEndAnElement() {
        String message = "UNH+1+MEDLAB:1'KOP+NAAR+De Vries?+Jansen+++'UNT+3+1'";

        assertEquals("UNH+1+MEDLAB:1'KOP+NAAR+***+++'UNT+3+1'", EdifactMasker.mask(message));
    }

    @Test
    public void usesTheDelimitersOfTheUna() {
        // element separator /, release character #, segment terminator !
        String message = "UNA:/.# !\nUNB/UNOA:1/SENDER/RECEIVER/060921:1652/1!\nUNH/1/MEDLAB:1!\nPID/2003:04:22/V/Jansen#/Zn:::::P./2!\n"
                + "BEP:1:1:1/0/gewicht/70.0//kg//0/1000/GEW!\nUNT/4/1!\nUNZ/1/1!";

        assertEquals("UNA:/.# !\nUNB/UNOA:1/SENDER/RECEIVER/060921:1652/1!\nUNH/1/MEDLAB:1!\nPID/***/V/***/***!\n"
                + "BEP:1:1:1/0/gewicht/70.0//kg//0/1000/GEW!\nUNT/4/1!\nUNZ/1/1!", EdifactMasker.mask(message));
    }

    @Test
    public void masksLongRemarksInHealthMessages() {
        String message = "UNH+1+MEDLAB:1'OPB+nuchter afgenomen'COM+Uitslag besproken met mevrouw De Vries op 12 maart'UNT+4+1'";

        assertEquals("UNH+1+MEDLAB:1'OPB+nuchter afgenomen'COM+***'UNT+4+1'", EdifactMasker.mask(message));
    }

    @Test
    public void keepsLongHospitalAndLaboratoryNames() throws Exception {
        String hospital = "Stichting Noordwest Ziekenhuisgroep"; // 35 characters
        String lab = "Klinisch chemisch en hematologisch laboratorium";
        String raw = "UNH+1+MEDLAB:1'ZKH+" + hospital + "+Wilhelminalaan:12::Alkmaar:1815JD'AFD+" + lab + "+'COM:1:1+" + lab + "'UNT+5+1'";

        assertEquals("UNH+1+MEDLAB:1'ZKH+" + hospital + "+Wilhelminalaan:12::Alkmaar:1815JD'AFD+" + lab + "+'COM:1:1+***'UNT+5+1'", masker.mask(raw));

        String xml = resource("medlab.xml").replace("<ZKH.01.1>Testzorg<", "<ZKH.01.1>" + hospital + "<")
                .replace("<AFD.01.1>Diabetes verpleegkundige<", "<AFD.01.1>" + lab + "<");
        String masked = masker.mask(xml);
        assertTrue(masked.contains("<ZKH.01.1>" + hospital + "</ZKH.01.1>"));
        assertTrue(masked.contains("<AFD.01.1>" + lab + "</AFD.01.1>"));
    }

    @Test
    public void hl7IsStillMasked() {
        String hl7 = "MSH|^~\\&|LAB|FAC\rPID|1||PID12345^^^MRN||Doe^John^A||19800101|M\r";

        assertEquals("MSH|^~\\&|LAB|FAC\rPID|1||***||***||***|M\r", masker.mask(hl7));
    }
}
