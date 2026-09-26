package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data in FHIR resources, in JSON and in XML. Part of {@link Masker}.
 *
 * FHIR has to be masked by structure: "name" is personal data in a Patient but not in a ValueSet.
 * JSON is therefore parsed (keeping the position of every value), so only the right values are
 * replaced and the rest of the text stays exactly as it was. XML is masked per resource element.
 *
 * Masked:
 * <ul>
 * <li>in Patient, RelatedPerson, Person and Practitioner: name, identifier, telecom, address,
 * birthDate, photo and contact, as a whole;</li>
 * <li>in every resource: the value of each identifier, and the display of each reference;</li>
 * <li>the narrative (text.div), which repeats the data in readable form.</li>
 * </ul>
 * Structure, resource types and codes stay readable.
 */
final class FhirMasker {

    static final Set<String> PERSON_RESOURCES = Set.of("Patient", "RelatedPerson", "Person", "Practitioner");
    static final Set<String> PERSON_ELEMENTS = Set.of("name", "identifier", "telecom", "address", "birthDate", "photo", "contact");

    /**
     * Extension values in a person resource that can hold personal data (a mother's maiden name, a
     * birthplace, a date). Coded values (valueCode, valueCoding, valueCodeableConcept, valueBoolean)
     * stay readable.
     */
    static final Set<String> PERSON_EXTENSION_VALUES = Set.of("valueString", "valueHumanName", "valueAddress", "valueIdentifier", "valueContactPoint",
            "valueDate", "valueDateTime", "valueInstant", "valuePeriod", "valueAttachment", "valueMarkdown", "valueReference", "valueAge");

    private static final String MASKED_JSON = "\"" + Masker.MASK + "\"";

    private FhirMasker() {}

    static String mask(String text) {
        return maskXml(maskJson(text));
    }

    // ------------------------------------------------------------------ JSON

    /** Every JSON document in the text with a "resourceType" is masked; everything else is left alone. */
    static String maskJson(String text) {
        if (text.indexOf("\"resourceType\"") < 0) {
            return text;
        }
        List<int[]> spans = new ArrayList<>();
        int i = 0;
        while ((i = text.indexOf('{', i)) >= 0) {
            if (!startsObject(text, i)) {
                i++;
                continue;
            }
            Node root;
            try {
                root = new JsonReader(text, i).read();
            } catch (IllegalArgumentException e) {
                i++;
                continue;
            }
            if (root.end > i && containsResource(root)) {
                walk(root, spans);
                i = root.end;
            } else {
                i++;
            }
        }
        if (spans.isEmpty()) {
            return text;
        }
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        StringBuilder sb = new StringBuilder(text.length());
        int last = 0;
        for (int[] span : spans) {
            if (span[0] < last) {
                continue; // inside a value that is masked as a whole already
            }
            sb.append(text, last, span[0]).append(MASKED_JSON);
            last = span[1];
        }
        return sb.append(text, last, text.length()).toString();
    }

    /** '{' followed by a property name: the start of a JSON object, not a brace in prose or code. */
    private static boolean startsObject(String text, int i) {
        int j = i + 1;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        return j < text.length() && text.charAt(j) == '"';
    }

    private static boolean containsResource(Node node) {
        if (node.type == Node.OBJECT) {
            Node rt = node.fields.get("resourceType");
            if (rt != null && rt.type == Node.STRING) {
                return true;
            }
            for (Node child : node.fields.values()) {
                if (containsResource(child)) {
                    return true;
                }
            }
        } else if (node.type == Node.ARRAY) {
            for (Node child : node.items) {
                if (containsResource(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void walk(Node node, List<int[]> spans) {
        walk(node, spans, false);
    }

    /** @param inPerson inside a person resource: its extensions are masked too */
    private static void walk(Node node, List<int[]> spans, boolean inPerson) {
        if (node.type == Node.ARRAY) {
            for (Node item : node.items) {
                walk(item, spans, inPerson);
            }
            return;
        }
        if (node.type != Node.OBJECT) {
            return;
        }
        Node rt = node.fields.get("resourceType");
        boolean resource = rt != null && rt.type == Node.STRING;
        boolean person = resource && PERSON_RESOURCES.contains(rt.string);
        if (resource) {
            inPerson = person; // a contained or nested resource decides for itself
        }
        if (inPerson && node.fields.containsKey("url")) {
            // An extension of a person: mask the values that can hold personal data.
            for (Map.Entry<String, Node> field : node.fields.entrySet()) {
                if (PERSON_EXTENSION_VALUES.contains(field.getKey())) {
                    spans.add(new int[] { field.getValue().start, field.getValue().end });
                }
            }
        }
        // A reference: { "reference": "Patient/1", "display": "Piet Jansen" }
        Node reference = node.fields.get("reference");
        Node display = node.fields.get("display");
        if (reference != null && reference.type == Node.STRING && display != null && display.type == Node.STRING) {
            spans.add(new int[] { display.start, display.end });
        }
        for (Map.Entry<String, Node> field : node.fields.entrySet()) {
            String key = field.getKey();
            Node value = field.getValue();
            String base = key.startsWith("_") ? key.substring(1) : key;
            if (person && PERSON_ELEMENTS.contains(base)) {
                spans.add(new int[] { value.start, value.end });
            } else if (key.equals("identifier")) {
                maskIdentifierValues(value, spans);
                walk(value, spans, inPerson);
            } else if (key.equals("text") && value.type == Node.OBJECT && value.fields.containsKey("div")) {
                Node div = value.fields.get("div");
                spans.add(new int[] { div.start, div.end });
            } else {
                walk(value, spans, inPerson);
            }
        }
    }

    private static void maskIdentifierValues(Node identifier, List<int[]> spans) {
        if (identifier.type == Node.ARRAY) {
            for (Node item : identifier.items) {
                maskIdentifierValues(item, spans);
            }
        } else if (identifier.type == Node.OBJECT) {
            Node value = identifier.fields.get("value");
            if (value != null) {
                spans.add(new int[] { value.start, value.end });
            }
        }
    }

    /** A JSON value and where it is in the text. */
    private static final class Node {
        static final int OBJECT = 0, ARRAY = 1, STRING = 2, OTHER = 3;

        final int type;
        final int start;
        int end;
        String string;
        final Map<String, Node> fields = new LinkedHashMap<>();
        final List<Node> items = new ArrayList<>();

        Node(int type, int start) {
            this.type = type;
            this.start = start;
        }
    }

    /** A JSON reader that only records structure and positions. Throws on anything that is not JSON. */
    private static final class JsonReader {
        private final String s;
        private int pos;

        JsonReader(String s, int pos) {
            this.s = s;
            this.pos = pos;
        }

        Node read() {
            return value();
        }

        private Node value() {
            skipWhitespace();
            if (pos >= s.length()) {
                throw new IllegalArgumentException();
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return object();
            }
            if (c == '[') {
                return array();
            }
            if (c == '"') {
                Node n = new Node(Node.STRING, pos);
                n.string = string();
                n.end = pos;
                return n;
            }
            Node n = new Node(Node.OTHER, pos);
            while (pos < s.length() && "-+.0123456789eEtruefalsn".indexOf(s.charAt(pos)) >= 0) {
                pos++;
            }
            if (pos == n.start) {
                throw new IllegalArgumentException();
            }
            n.end = pos;
            return n;
        }

        private Node object() {
            Node n = new Node(Node.OBJECT, pos++);
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                n.end = pos;
                return n;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw new IllegalArgumentException();
                }
                String key = string();
                skipWhitespace();
                if (next() != ':') {
                    throw new IllegalArgumentException();
                }
                n.fields.put(key, value());
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    n.end = pos;
                    return n;
                }
                if (c != ',') {
                    throw new IllegalArgumentException();
                }
            }
        }

        private Node array() {
            Node n = new Node(Node.ARRAY, pos++);
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                n.end = pos;
                return n;
            }
            while (true) {
                n.items.add(value());
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    n.end = pos;
                    return n;
                }
                if (c != ',') {
                    throw new IllegalArgumentException();
                }
            }
        }

        private String string() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    if (e == 'u') {
                        pos += 4;
                    }
                    sb.append(e);
                } else if (c == '\n' || c == '\r') {
                    throw new IllegalArgumentException();
                } else {
                    sb.append(c);
                }
            }
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        private char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException();
            }
            return s.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }
    }

    // ------------------------------------------------------------------ XML

    /** A person resource element, e.g. <Patient xmlns="http://hl7.org/fhir">...</Patient>. */
    private static final Pattern XML_PERSON = Pattern.compile("<(" + String.join("|", PERSON_RESOURCES) + ")(\\s[^>]*)?>([\\s\\S]*?)</\\1>");
    /** A personal element directly or deeper inside it: complex (<name>...</name>) or primitive (<birthDate value="..."/>). */
    private static final Pattern XML_PERSON_ELEMENT = Pattern.compile("<(" + String.join("|", PERSON_ELEMENTS) + ")(\\s[^>]*?)?(/>|>[\\s\\S]*?</\\1>)");
    /** An extension value in a person resource that can hold personal data (see PERSON_EXTENSION_VALUES). */
    private static final Pattern XML_PERSON_EXTENSION_VALUE = Pattern.compile("<(" + String.join("|", PERSON_EXTENSION_VALUES) + ")(\\s[^>]*?)?(/>|>[\\s\\S]*?</\\1>)");
    /** The value of an identifier, in any resource. */
    private static final Pattern XML_IDENTIFIER = Pattern.compile("(<identifier(?:\\s[^>]*)?>(?:(?!</identifier>)[\\s\\S])*?<value\\s+value=\")[^\"]*(\")");
    /** The display of a reference, not of a coding: it follows a <reference> without a <code> or <system> in between. */
    private static final Pattern XML_REFERENCE_DISPLAY = Pattern.compile("(<reference\\s+value=\"[^\"]*\"\\s*/>(?:(?!<(?:code|system|coding|reference)\\b|</)[\\s\\S])*?<display\\s+value=\")[^\"]*(\")");
    /** XML comments: in FHIR examples and exports they often describe the person ("Peter James Chalmers, but called Jim"). */
    private static final Pattern XML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    private static final String MASKED_COMMENT = "<!-- " + Masker.MASK + " -->";
    private static final String FHIR_NAMESPACE = "http://hl7.org/fhir";
    /** The narrative: everything in text.div, including nested divs. */
    private static final Pattern XML_NARRATIVE = Pattern.compile("(<text>\\s*(?:<status\\s[^>]*/>\\s*)?<div\\b[^>]*>)[\\s\\S]*?(</div>\\s*</text>)");

    static String maskXml(String text) {
        if (text.indexOf('<') < 0 || !(text.contains("<Patient") || text.contains("<identifier") || text.contains("<reference") || text.contains("<text>")
                || text.contains("<RelatedPerson") || text.contains("<Person") || text.contains("<Practitioner"))) {
            return text;
        }
        String out = XML_NARRATIVE.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(Masker.MASK) + "$2");
        if (out.contains(FHIR_NAMESPACE)) {
            // In a FHIR document every comment may describe a person, not only those inside a Patient.
            out = XML_COMMENT.matcher(out).replaceAll(Matcher.quoteReplacement(MASKED_COMMENT));
        }
        out = replace(XML_PERSON, out, m -> "<" + m.group(1) + (m.group(2) == null ? "" : m.group(2)) + ">" + maskPersonElements(m.group(3)) + "</" + m.group(1) + ">");
        out = XML_IDENTIFIER.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(Masker.MASK) + "$2");
        out = XML_REFERENCE_DISPLAY.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(Masker.MASK) + "$2");
        return out;
    }

    private static String maskPersonElements(String body) {
        String out = XML_COMMENT.matcher(body).replaceAll(Matcher.quoteReplacement(MASKED_COMMENT));
        out = replace(XML_PERSON_EXTENSION_VALUE, out, FhirMasker::maskElement);
        return replace(XML_PERSON_ELEMENT, out, FhirMasker::maskElement);
    }

    /** A primitive keeps its element with the value masked; the content (a complex value, or extensions) is masked as a whole. */
    private static String maskElement(Matcher m) {
        String attributes = m.group(2) == null ? "" : m.group(2).replaceAll("\\bvalue=\"[^\"]*\"", "value=\"" + Matcher.quoteReplacement(Masker.MASK) + "\"");
        if (m.group(3).equals("/>")) {
            return "<" + m.group(1) + attributes + "/>";
        }
        return "<" + m.group(1) + attributes + ">" + Masker.MASK + "</" + m.group(1) + ">";
    }

    private interface Replacer {
        String apply(Matcher m);
    }

    private static String replace(Pattern pattern, String text, Replacer replacer) {
        Matcher m = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(replacer.apply(m)));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
