package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;
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

    /** HL7 v2 PID fields: identifiers, name, mother's maiden name, DOB, address, phone home/business, SSN. */
    private static final int[] HL7_PID_FIELDS = { 2, 3, 4, 5, 6, 7, 9, 11, 13, 14, 19 };
    private static final String PID_IDS = "2|3|4|5|6|7|9|11|13|14|19";

    /** Raw GDT lines: 3-digit length + 4-digit field id + content. */
    private static final Pattern GDT_RAW = Pattern.compile("(?m)^(\\d{3})(" + GDT_IDS + ")(.*)$");

    /** GDT XML as produced by the GDT data type plugin: <F3101 name="Patient name">...</F3101> */
    private static final Pattern GDT_XML = Pattern.compile("(<F(" + GDT_IDS + ")\\b[^>]*>)[\\s\\S]*?(</F\\2>)");

    /** HL7 v2 as OIE XML: <PID.5><PID.5.1>...</PID.5.1></PID.5> */
    private static final Pattern HL7_XML = Pattern.compile("(<PID\\.(" + PID_IDS + ")>)[\\s\\S]*?(</PID\\.\\2>)");

    /** HL7 v2 ER7 PID segments. Segment separator is \r, \n or both; field separator follows "PID". */
    private static final Pattern HL7_ER7 = Pattern.compile("(^|[\\r\\n])PID(.)([^\\r\\n]*)");

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
        String out = maskHl7Er7(text);
        out = HL7_NTE_ER7.matcher(out).replaceAll("$1NTE$2" + Matcher.quoteReplacement(MASK));
        out = replace(HL7_SEGMENT, out, m -> m.group(1) + m.group(2) + HL7_LONG_VALUE.matcher(m.group(3)).replaceAll(Matcher.quoteReplacement(MASK)));
        out = HL7_XML.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(MASK) + "$3");
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
            String sep = m.group(2);
            String[] fields = m.group(3).split(Pattern.quote(sep), -1);
            for (int n : HL7_PID_FIELDS) {
                if (n - 1 < fields.length && !fields[n - 1].isEmpty()) {
                    fields[n - 1] = MASK;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + "PID" + sep + String.join(sep, fields)));
        }
        m.appendTail(sb);
        return sb.toString();
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
