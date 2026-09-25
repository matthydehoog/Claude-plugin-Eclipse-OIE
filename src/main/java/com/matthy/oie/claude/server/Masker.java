package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Best-effort masking of patient identifiers before anything is sent to Claude. Port of mask.ts from
 * the oie-mcp-server. This is a safety net, not an anonymisation guarantee.
 */
public class Masker {

    static final String MASK = "***";

    /** GDT patient fields: number, name prefix, name, first name, birth date, title, insurance nr, residence, street. */
    private static final String GDT_IDS = "3000|3100|3101|3102|3103|3104|3105|3106|3107";

    /**
     * HL7 v2 fields masked per segment.
     * PID: identifiers, name, mother's maiden name, DOB, alias, address, phone home/business,
     * account number, SSN, driver's licence, mother's identifier, birthplace, date of death.
     * PV1: attending, referring and consulting doctor, admitting doctor, visit number, alternate visit ID.
     */
    private static final Map<String, Set<Integer>> HL7_FIELDS = Map.of(
            "PID", Set.of(2, 3, 4, 5, 6, 7, 9, 11, 13, 14, 18, 19, 20, 21, 23, 29),
            "PV1", Set.of(7, 8, 9, 17, 19, 50));

    /**
     * HL7 v2 segments that are about a person as a whole (next of kin, guarantor, insurance, merged
     * patient identifiers): every field from the given one onwards is masked, keeping only the set ID.
     */
    private static final Map<String, Integer> HL7_WHOLE_SEGMENTS = Map.of("NK1", 2, "GT1", 2, "IN1", 2, "IN2", 1, "MRG", 1);

    private static final String HL7_MASKED_SEGMENTS = "PID|PV1|NK1|GT1|IN1|IN2|MRG";

    /** Raw GDT lines: 3-digit length + 4-digit field id + content. */
    private static final Pattern GDT_RAW = Pattern.compile("(?m)^(\\d{3})(" + GDT_IDS + ")(.*)$");

    /** GDT XML as produced by the GDT data type plugin: <F3101 name="Patient name">...</F3101> */
    private static final Pattern GDT_XML = Pattern.compile("(<F(" + GDT_IDS + ")\\b[^>]*>)[\\s\\S]*?(</F\\2>)");

    /** HL7 v2 as OIE XML: a field element such as <PID.5><PID.5.1>...</PID.5.1></PID.5>. */
    private static final Pattern HL7_XML = Pattern.compile("(<(" + HL7_MASKED_SEGMENTS + ")\\.(\\d+)>)[\\s\\S]*?(</\\2\\.\\3>)");

    /** HL7 v2 ER7 segments with masked fields. Segment separator is \r, \n or both; the field separator follows the segment name. */
    private static final Pattern HL7_ER7 = Pattern.compile("(^|[\\r\\n])(" + HL7_MASKED_SEGMENTS + ")(.)([^\\r\\n]*)");

    /**
     * Text values longer than this are masked in every HL7 segment: free-text notes, report text,
     * base64 documents (e.g. a PDF in OBX-5). Short coded values stay readable.
     */
    static final int HL7_MAX_TEXT = 30;

    /** HL7 v2 ER7 NTE segments: everything after NTE-2 (the comment and its type) is masked. */
    private static final Pattern HL7_NTE_ER7 = Pattern.compile("(^|[\\r\\n])NTE(\\|[^|\\r\\n]*\\|[^|\\r\\n]*\\|)([^\\r\\n]+)");

    /** HL7 v2 as OIE XML: the NTE comment <NTE.3>...</NTE.3>. */
    private static final Pattern HL7_NTE_XML = Pattern.compile("(<NTE\\.3>)[\\s\\S]*?(</NTE\\.3>)");

    /** Any HL7 v2 ER7 segment line (field separator |). */
    private static final Pattern HL7_SEGMENT = Pattern.compile("(^|[\\r\\n])([A-Z][A-Z0-9]{2}\\|)([^\\r\\n]*)");

    /** A value between HL7 delimiters (| ^ ~ &) that is longer than {@link #HL7_MAX_TEXT}. */
    private static final Pattern HL7_LONG_VALUE = Pattern.compile("[^|^~&\\r\\n]{" + (HL7_MAX_TEXT + 1) + ",}");

    /** HL7 v2 as OIE XML: a leaf element such as <OBX.5.1> with more than {@link #HL7_MAX_TEXT} characters of text. */
    private static final Pattern HL7_XML_LONG = Pattern.compile("(<([A-Z][A-Z0-9]{2}\\.\\d+(?:\\.\\d+)*)>)([^<]{" + (HL7_MAX_TEXT + 1) + ",})(</\\2>)");

    private static final Pattern NINE_DIGITS = Pattern.compile("(?<!\\d)\\d{9}(?!\\d)");

    /**
     * XML text holding escaped content, as the tools return it: message content serialized by
     * XStream has segment separators as &amp;#xd; and transformed XML as &amp;lt;PID.5&amp;gt;.
     * Such text is unescaped, masked with every rule and escaped again.
     */
    private static final Pattern ESCAPED_TEXT = Pattern.compile(">([^<]*&(?:#x[0-9a-fA-F]+|#\\d+|lt|gt|amp|quot|apos);[^<]*)<");
    private static final Pattern ENTITY = Pattern.compile("&(?:#x([0-9a-fA-F]+)|#(\\d+)|(lt|gt|amp|quot|apos));");

    /** Credentials in serialized configuration, e.g. a database reader's or SFTP connector's password. */
    private static final Pattern CREDENTIAL_XML = Pattern.compile("(?i)(<([\\w.]*(?:password|passphrase|secret|token|apikey)[\\w.]*)>)[^<]+(</\\2>)");

    private final List<Pattern> extraPatterns;

    /** @param extraPatterns regular expressions separated by ";;", masked in addition to the built-in rules */
    public Masker(String extraPatterns) {
        this.extraPatterns = new ArrayList<>();
        if (extraPatterns != null) {
            for (String p : extraPatterns.split(";;")) {
                if (!p.isBlank()) {
                    try {
                        this.extraPatterns.add(Pattern.compile(p));
                    } catch (PatternSyntaxException e) {
                        throw new IllegalArgumentException("Invalid mask pattern '" + p + "': " + e.getDescription());
                    }
                }
            }
        }
    }

    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = replace(ESCAPED_TEXT, text, m -> ">" + escapeXml(mask(unescapeXml(m.group(1)))) + "<");
        out = CREDENTIAL_XML.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$3");
        out = maskHl7Er7(out);
        out = HL7_NTE_ER7.matcher(out).replaceAll("$1NTE$2" + Matcher.quoteReplacement(MASK));
        out = replace(HL7_SEGMENT, out, m -> m.group(1) + m.group(2) + HL7_LONG_VALUE.matcher(m.group(3)).replaceAll(Matcher.quoteReplacement(MASK)));
        out = replace(HL7_XML, out, m -> maskField(m.group(2), Integer.parseInt(m.group(3))) ? m.group(1) + MASK + m.group(4) : m.group());
        out = HL7_NTE_XML.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$2");
        out = HL7_XML_LONG.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$4");
        out = GDT_RAW.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(MASK));
        out = GDT_XML.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$3");
        out = replace(NINE_DIGITS, out, m -> isBsn(m.group()) ? MASK : m.group());
        for (Pattern p : extraPatterns) {
            out = p.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return out;
    }

    private static String maskHl7Er7(String text) {
        Matcher m = HL7_ER7.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String segment = m.group(2);
            String sep = m.group(3);
            String[] fields = m.group(4).split(Pattern.quote(sep), -1);
            for (int n = 1; n <= fields.length; n++) {
                if (!fields[n - 1].isEmpty() && maskField(segment, n)) {
                    fields[n - 1] = MASK;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + segment + sep + String.join(sep, fields)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Whether field n (1-based) of this segment is masked. */
    static boolean maskField(String segment, int n) {
        Integer from = HL7_WHOLE_SEGMENTS.get(segment);
        if (from != null) {
            return n >= from;
        }
        Set<Integer> fields = HL7_FIELDS.get(segment);
        return fields != null && fields.contains(n);
    }

    static String unescapeXml(String s) {
        return replace(ENTITY, s, m -> {
            if (m.group(3) != null) {
                switch (m.group(3)) {
                    case "lt": return "<";
                    case "gt": return ">";
                    case "amp": return "&";
                    case "quot": return "\"";
                    default: return "'";
                }
            }
            try {
                return new String(Character.toChars(m.group(1) != null ? Integer.parseInt(m.group(1), 16) : Integer.parseInt(m.group(2))));
            } catch (IllegalArgumentException e) {
                return m.group();
            }
        });
    }

    /** Escapes like XStream does for text: &amp;, &lt;, &gt; and carriage return. */
    static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#xd;");
    }

    /** Dutch BSN: 9 digits passing the 11-proof. Masks some false positives, which is the safe side. */
    static boolean isBsn(String digits) {
        if ("000000000".equals(digits)) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (digits.charAt(i) - '0') * (9 - i);
        }
        sum -= digits.charAt(8) - '0';
        return sum % 11 == 0;
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
