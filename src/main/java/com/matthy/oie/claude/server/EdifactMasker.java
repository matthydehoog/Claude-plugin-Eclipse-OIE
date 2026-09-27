package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal data in EDIFACT health messages, raw and as XML from the EDIFACT data type
 * (&lt;PID&gt;&lt;PID.03&gt;...). Part of {@link Masker}.
 *
 * The message type (UNH.02.1) decides what is masked:
 * <ul>
 * <li><b>MEDLAB</b> (Dutch laboratory results): PID birth date, name and patient numbers, PAD
 * (address, phone), ART doctor name and address, ARA and KOP names, IDE identification number;</li>
 * <li><b>other health messages</b> (MED*: MEDSPE, MEDVRY, MEDEUR, MEDRPT, MEDREQ, ...): PID and NAD
 * after their qualifier, PNA, ADR, COM, a date of birth (DTM with BTH or 329) and the free text of
 * FTX;</li>
 * <li>in both: every component longer than {@link Masker#HL7_MAX_TEXT} characters (remarks, letter
 * text).</li>
 * </ul>
 * Trade messages (ORDERS, INVOIC, ...) are left alone. The segment tags, qualifiers, data element 0
 * (ARA:1, BEP:1:1:3) and the results (BEP) stay readable. Raw messages are read with their own
 * delimiters (UNA) and release character, so an escaped ?+ does not end a data element.
 */
final class EdifactMasker {

    private enum Kind { MEDLAB, HEALTH, UNKNOWN, TRADE }

    /** The start of an interchange or message: a UNA service string advice, or a UNB/UNH segment. */
    private static final Pattern START = Pattern.compile("(?<![A-Za-z0-9])(?:UNA[\\s\\S]{6}|UN[BH](?=[+:]))");

    private static final Pattern TAG = Pattern.compile("[A-Z][A-Z0-9]{2}");

    /** XML from the EDIFACT data type: one interchange or bare message, up to its end or the end of the text. */
    private static final Pattern XML_ROOT = Pattern.compile("<(EDIFACTInterchange|EDIFACTMessage)\\b[^>]*>[\\s\\S]*?(?:</\\1>|$)");

    /** A data element of the EDIFACT XML (two-digit number, unlike HL7's PID.5). */
    private static final Pattern XML_ELEMENT = Pattern.compile("<[A-Z][A-Z0-9]{2}\\.\\d{2}>");
    private static final Pattern XML_SEGMENT = Pattern.compile("<([A-Z][A-Z0-9]{2})>([\\s\\S]*?)</\\1>");
    private static final Pattern XML_TYPE = Pattern.compile("<UNH\\.02\\.1>([^<]*)</UNH\\.02\\.1>");

    private static final Set<String> BIRTH_DATE_QUALIFIERS = Set.of("BTH", "329");

    private EdifactMasker() {}

    static String mask(String text) {
        return maskXml(maskRaw(text));
    }

    // ------------------------------------------------------------------ rules

    /** Whether data element n (1-based) of this segment is masked as a whole. */
    private static boolean maskElement(Kind kind, String tag, int n) {
        switch (kind) {
            case TRADE:
                return false;
            case MEDLAB:
                return maskMedlab(tag, n);
            case HEALTH:
                return maskHealth(tag, n);
            default: // no UNH seen: both sets, and all of PID
                return tag.equals("PID") || maskMedlab(tag, n) || maskHealth(tag, n);
        }
    }

    private static boolean maskMedlab(String tag, int n) {
        switch (tag) {
            case "PID": return n != 2; // birth date, name, patient numbers; the sex stays
            case "PAD": return true;
            case "ART": return n >= 3; // doctor name and address; kind and code stay
            case "ARA": return true;
            case "KOP": return n >= 2; // names; VAN/NAAR stays
            case "IDE": return n == 2; // identification number
            default: return false;
        }
    }

    private static boolean maskHealth(String tag, int n) {
        switch (tag) {
            case "PID":
            case "NAD":
            case "PNA":
            case "ADR": return n >= 2; // after the qualifier
            case "COM": return n >= 1;
            case "FTX": return n == 4; // the text
            default: return false;
        }
    }

    private static Kind kind(String messageType) {
        if (messageType == null) {
            return Kind.UNKNOWN;
        }
        String type = messageType.trim().toUpperCase();
        if (type.equals("MEDLAB")) {
            return Kind.MEDLAB;
        }
        return type.startsWith("MED") ? Kind.HEALTH : Kind.TRADE;
    }

    // ------------------------------------------------------------------ raw

    static String maskRaw(String text) {
        if (text.indexOf("UN") < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int copied = 0;
        Matcher m = START.matcher(text);
        int from = 0;
        while (from < text.length() && m.find(from)) {
            int start = m.start();
            Delimiters d = Delimiters.DEFAULT;
            int pos = start;
            if (text.startsWith("UNA", start)) {
                d = new Delimiters(text.charAt(start + 3), text.charAt(start + 4), text.charAt(start + 6), text.charAt(start + 8));
                pos = start + 9;
            }
            out.append(text, copied, pos);
            int end = maskInterchange(text, pos, d, out);
            copied = end;
            from = Math.max(end, start + 1);
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    /** Masks the segments from pos on and appends them; returns where the EDIFACT ends. */
    private static int maskInterchange(String text, int pos, Delimiters d, StringBuilder out) {
        Kind kind = Kind.UNKNOWN;
        while (pos < text.length()) {
            int segStart = pos;
            while (segStart < text.length() && (text.charAt(segStart) == '\r' || text.charAt(segStart) == '\n' || text.charAt(segStart) == ' ')) {
                segStart++;
            }
            if (segStart + 3 > text.length() || !TAG.matcher(text.substring(segStart, segStart + 3)).matches()) {
                return pos;
            }
            String tag = text.substring(segStart, segStart + 3);
            char next = segStart + 3 < text.length() ? text.charAt(segStart + 3) : d.terminator;
            if (next != d.element && next != d.component && next != d.terminator) {
                return pos;
            }
            int segEnd = segmentEnd(text, segStart + 3, d); // index of the terminator, line break or end
            String body = text.substring(segStart + 3, segEnd);
            List<String> elements = split(body, d.element, d.release);
            if (tag.equals("UNH") && elements.size() > 2) {
                kind = kind(unescape(split(elements.get(2), d.component, d.release).get(0), d.release));
            }
            out.append(text, pos, segStart).append(tag).append(maskSegment(kind, tag, elements, d));
            if (segEnd < text.length() && text.charAt(segEnd) == d.terminator) {
                out.append(d.terminator);
                segEnd++;
            }
            pos = segEnd;
            if (tag.equals("UNZ")) {
                return pos;
            }
        }
        return pos;
    }

    /** elements.get(0) is data element 0 (the components directly behind the tag, may be empty). */
    private static String maskSegment(Kind kind, String tag, List<String> elements, Delimiters d) {
        if (kind == Kind.TRADE || tag.startsWith("UN")) {
            return String.join(String.valueOf(d.element), elements);
        }
        List<String> masked = new ArrayList<>(elements);
        for (int n = 1; n < masked.size(); n++) {
            String element = masked.get(n);
            if (element.isEmpty()) {
                continue;
            }
            if (maskElement(kind, tag, n)) {
                masked.set(n, Masker.MASK);
                continue;
            }
            List<String> components = split(element, d.component, d.release);
            boolean birthDate = tag.equals("DTM") && n == 1 && BIRTH_DATE_QUALIFIERS.contains(unescape(components.get(0), d.release));
            for (int c = 0; c < components.size(); c++) {
                String value = components.get(c);
                if (!value.isEmpty() && ((birthDate && c > 0) || unescape(value, d.release).length() > Masker.HL7_MAX_TEXT)) {
                    components.set(c, Masker.MASK);
                }
            }
            masked.set(n, String.join(String.valueOf(d.component), components));
        }
        return String.join(String.valueOf(d.element), masked);
    }

    private static int segmentEnd(String text, int i, Delimiters d) {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == d.release && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == d.terminator || c == '\r' || c == '\n') {
                return i;
            }
            i++;
        }
        return i;
    }

    /** Splits on a delimiter that is not released; the parts keep their release characters. */
    private static List<String> split(String s, char delimiter, char release) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == release) {
                i++;
            } else if (c == delimiter) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static String unescape(String s, char release) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == release && i + 1 < s.length()) {
                c = s.charAt(++i);
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static final class Delimiters {
        static final Delimiters DEFAULT = new Delimiters(':', '+', '?', '\'');

        final char component;
        final char element;
        final char release;
        final char terminator;

        Delimiters(char component, char element, char release, char terminator) {
            this.component = component;
            this.element = element;
            this.release = release;
            this.terminator = terminator;
        }
    }

    // ------------------------------------------------------------------ XML

    static String maskXml(String text) {
        if (!XML_ELEMENT.matcher(text).find()) {
            return text;
        }
        // Whole documents by their message type; segments outside one (a pasted fragment) as unknown.
        StringBuilder out = new StringBuilder();
        Matcher root = XML_ROOT.matcher(text);
        int copied = 0;
        while (root.find()) {
            out.append(maskXmlSegments(text.substring(copied, root.start()), Kind.UNKNOWN));
            out.append(maskXmlSegments(root.group(), null));
            copied = root.end();
        }
        out.append(maskXmlSegments(text.substring(copied), Kind.UNKNOWN));
        return out.toString();
    }

    /** @param fixed the kind to use, or null to take it from each UNH */
    private static String maskXmlSegments(String text, Kind fixed) {
        if (!XML_ELEMENT.matcher(text).find()) {
            return text;
        }
        Kind[] kind = { fixed == null ? Kind.UNKNOWN : fixed };
        return replace(XML_SEGMENT, text, seg -> {
            String tag = seg.group(1);
            if (tag.equals("UNH") && fixed == null) {
                Matcher type = XML_TYPE.matcher(seg.group());
                if (type.find()) {
                    kind[0] = kind(type.group(1));
                }
            }
            if (kind[0] == Kind.TRADE || tag.startsWith("UN")) {
                return seg.group();
            }
            return "<" + tag + ">" + maskXmlSegment(kind[0], tag, seg.group(2)) + "</" + tag + ">";
        });
    }

    private static String maskXmlSegment(Kind kind, String tag, String body) {
        Pattern element = Pattern.compile("<(" + tag + "\\.(\\d{2}))>([\\s\\S]*?)</\\1>");
        Pattern component = Pattern.compile("(<(" + tag + "\\.\\d{2}\\.(\\d+))>)([^<]*)(</\\2>)");
        return replace(element, body, e -> {
            int n = Integer.parseInt(e.group(2));
            if (n == 0) {
                return e.group();
            }
            if (maskElement(kind, tag, n)) {
                return "<" + e.group(1) + ">" + Masker.MASK + "</" + e.group(1) + ">";
            }
            boolean birthDate = tag.equals("DTM") && n == 1 && e.group(3).matches("(?s)\\s*<DTM\\.01\\.1>(BTH|329)</DTM\\.01\\.1>.*");
            String inner = replace(component, e.group(3), c -> {
                boolean mask = !c.group(4).isEmpty() && ((birthDate && !c.group(3).equals("1")) || Masker.unescapeXml(c.group(4)).length() > Masker.HL7_MAX_TEXT);
                return mask ? c.group(1) + Masker.MASK + c.group(5) : c.group();
            });
            return "<" + e.group(1) + ">" + inner + "</" + e.group(1) + ">";
        });
    }

    private interface Replacer {
        String apply(Matcher match);
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
