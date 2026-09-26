package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** FHIR through the whole masker, with examples from the FHIR specification (build.fhir.org). */
public class FhirMaskerTest {

    private final Masker masker = new Masker(null);

    private static String resource(String name) throws Exception {
        try (InputStream in = FhirMaskerTest.class.getResourceAsStream("/fhir/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertGone(String out, String... values) {
        for (String value : values) {
            assertFalse("still contains " + value + ":\n" + out, out.contains(value));
        }
    }

    @Test
    public void masksThePatientExampleInJson() throws Exception {
        String out = masker.mask(resource("patient-example.json"));
        assertGone(out, "Chalmers", "Windsor", "du Marché", "Bénédicte", "1974-12-25", "PleasantVille", "5555 6473", "\"12345\"", "Acme Healthcare");
        // Structure and non-personal data stay readable.
        assertTrue(out, out.contains("\"resourceType\" : \"Patient\""));
        assertTrue(out, out.contains("\"gender\" : \"male\""));
        assertTrue(out, out.contains("\"active\" : true"));
        assertTrue(out, out.contains("\"reference\" : \"Organization/1\""));
        assertTrue(out, out.contains("\"name\" : \"***\""));
        assertTrue(out, out.contains("\"_birthDate\" : \"***\""));
    }

    @Test
    public void masksPersonalExtensionsButKeepsCodes() {
        String json = "{\"resourceType\":\"Patient\",\"extension\":["
                + "{\"url\":\"http://hl7.org/fhir/StructureDefinition/patient-mothersMaidenName\",\"valueString\":\"Organa\"},"
                + "{\"url\":\"http://hl7.org/fhir/StructureDefinition/patient-genderIdentity\",\"valueCodeableConcept\":{\"coding\":[{\"code\":\"non-binary\"}]}},"
                + "{\"url\":\"recorded\",\"extension\":[{\"url\":\"effectivePeriod\",\"valuePeriod\":{\"start\":\"1974-12-25\"}}]}],\"gender\":\"other\"}";
        String out = masker.mask(json);
        assertGone(out, "Organa", "1974-12-25");
        assertTrue(out, out.contains("non-binary"));

        String xml = "<Patient xmlns=\"http://hl7.org/fhir\"><extension url=\"http://hl7.org/fhir/StructureDefinition/patient-mothersMaidenName\"><valueString value=\"Organa\"/></extension>"
                + "<extension url=\"recorded\"><extension url=\"effectivePeriod\"><valuePeriod><start value=\"1974-12-25\"/></valuePeriod></extension></extension>"
                + "<extension url=\"http://hl7.org/fhir/StructureDefinition/patient-genderIdentity\"><valueCodeableConcept><coding><code value=\"non-binary\"/></coding></valueCodeableConcept></extension></Patient>";
        String outXml = masker.mask(xml);
        assertGone(outXml, "Organa", "1974-12-25");
        assertTrue(outXml, outXml.contains("<code value=\"non-binary\"/>"));
    }

    @Test
    public void masksTheNarrative() {
        String json = "{\"resourceType\":\"Observation\",\"text\":{\"status\":\"generated\",\"div\":\"<div xmlns=\\\"http://www.w3.org/1999/xhtml\\\">Heart rate of <b>Piet Jansen</b></div>\"},\"status\":\"final\"}";
        String out = masker.mask(json);
        assertGone(out, "Piet Jansen");
        assertTrue(out, out.contains("\"status\":\"generated\""));

        String xml = "<Observation xmlns=\"http://hl7.org/fhir\"><text><status value=\"generated\"/><div xmlns=\"http://www.w3.org/1999/xhtml\"><div>Heart rate of</div><b>Piet Jansen</b></div></text><status value=\"final\"/></Observation>";
        String outXml = masker.mask(xml);
        assertGone(outXml, "Piet Jansen");
        assertTrue(outXml, outXml.contains("<status value=\"final\"/>"));
    }

    @Test
    public void masksThePatientExampleInXml() throws Exception {
        String out = masker.mask(resource("patient-example.xml"));
        assertGone(out, "Chalmers", "Windsor", "du Marché", "Bénédicte", "1974-12-25", "PleasantVille", "5555 6473", "\"12345\"", "Acme Healthcare");
        assertTrue(out, out.contains("<gender value=\"male\"/>"));
        assertTrue(out, out.contains("<name>***</name>"));
        assertTrue(out, out.contains("<birthDate value=\"***\">***</birthDate>"));
        assertTrue(out, out.contains("<!-- *** -->"));
        assertTrue(out, out.contains("<reference value=\"Organization/1\"/>"));
    }

    @Test
    public void masksEveryPatientInABundle() throws Exception {
        String original = resource("patient-examples-general.json");
        String out = masker.mask(original);
        Matcher families = Pattern.compile("\"family\"\\s*:\\s*\"([^\"]+)\"").matcher(original);
        int count = 0;
        while (families.find()) {
            assertGone(out, "\"" + families.group(1) + "\"");
            count++;
        }
        assertTrue("the example should have several patients", count > 3);
        assertTrue(out.contains("\"resourceType\" : \"Bundle\""));
    }

    @Test
    public void keepsCodesAndOtherResourcesReadable() throws Exception {
        String out = masker.mask(resource("observation-bundle.json"));
        assertGone(out, "Jansen", "Piet", "1980-01-01");
        assertTrue(out, out.contains("\"display\": \"Heart rate\""));
        assertTrue(out, out.contains("\"code\": \"8867-4\""));
        assertTrue(out, out.contains("\"reference\": \"urn:uuid:61ebe359-bfdc-4613-8bf2-c5e300945f0a\""));
        assertTrue(out, out.contains("\"value\": 72"));
    }

    @Test
    public void masksFhirEscapedInsideTheToolsXml() throws Exception {
        // oie_get_message serializes the message as XML: the JSON is text there, with < and > escaped.
        String xml = "<content>" + Masker.escapeXml(resource("patient-example.json")) + "</content>";
        String out = masker.mask(xml);
        assertGone(out, "Chalmers", "1974-12-25", "PleasantVille");
        assertTrue(out, out.startsWith("<content>"));
    }

    @Test
    public void leavesNamesOfOtherResourcesAndPlainJsonAlone() {
        String valueSet = "{\"resourceType\":\"ValueSet\",\"name\":\"LabCodes\",\"status\":\"active\"}";
        assertEquals(valueSet, masker.mask(valueSet));
        String notFhir = "{\"name\":\"Jansen\",\"city\":\"Arnhem\"}";
        assertEquals(notFhir, masker.mask(notFhir));
    }

    @Test
    public void masksIdentifiersAndReferenceDisplaysInEveryResource() {
        String json = "{\"resourceType\":\"Observation\",\"identifier\":[{\"system\":\"urn:lab\",\"value\":\"LAB-4711\"}],"
                + "\"subject\":{\"reference\":\"Patient/1\",\"display\":\"Piet Jansen\"},"
                + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"8867-4\",\"display\":\"Heart rate\"}]}}";
        String out = masker.mask(json);
        assertGone(out, "LAB-4711", "Piet Jansen");
        assertTrue(out, out.contains("\"system\":\"urn:lab\""));
        assertTrue(out, out.contains("\"display\":\"Heart rate\""));

        String xml = "<Observation xmlns=\"http://hl7.org/fhir\"><identifier><system value=\"urn:lab\"/><value value=\"LAB-4711\"/></identifier>"
                + "<code><coding><system value=\"http://loinc.org\"/><code value=\"8867-4\"/><display value=\"Heart rate\"/></coding></code>"
                + "<subject><reference value=\"Patient/1\"/><display value=\"Piet Jansen\"/></subject></Observation>";
        String outXml = masker.mask(xml);
        assertGone(outXml, "LAB-4711", "Piet Jansen");
        assertTrue(outXml, outXml.contains("<display value=\"Heart rate\"/>"));
        assertTrue(outXml, outXml.contains("<reference value=\"Patient/1\"/>"));
    }
}
