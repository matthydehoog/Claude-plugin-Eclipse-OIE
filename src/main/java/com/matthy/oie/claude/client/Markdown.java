package com.matthy.oie.claude.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal Markdown to HTML 3.2 for Swing's JEditorPane: headings, paragraphs, lists, tables, code
 * blocks, bold, italic and inline code. Everything else is shown as escaped text.
 */
final class Markdown {

    private static final Pattern ORDERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    private static final Pattern INLINE = Pattern.compile("`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<!\\w)_(?!\\s)(.+?)(?<!\\s)_(?!\\w)");

    private Markdown() {}

    static String toHtml(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                flush(out, paragraph);
                StringBuilder code = new StringBuilder();
                i++;
                while (i < lines.length && !lines[i].trim().startsWith("```")) {
                    code.append(lines[i]).append('\n');
                    i++;
                }
                i++; // closing fence
                out.append("<pre>").append(escape(code.toString().replaceAll("\\n$", ""))).append("</pre>");
                continue;
            }
            if (trimmed.isEmpty()) {
                flush(out, paragraph);
                i++;
                continue;
            }
            Matcher heading = HEADING.matcher(trimmed);
            if (heading.matches()) {
                flush(out, paragraph);
                int level = Math.min(heading.group(1).length() + 1, 5);
                out.append("<h").append(level).append('>').append(inline(heading.group(2))).append("</h").append(level).append('>');
                i++;
                continue;
            }
            if (trimmed.matches("^(-{3,}|\\*{3,}|_{3,})$")) {
                flush(out, paragraph);
                out.append("<hr>");
                i++;
                continue;
            }
            if (trimmed.startsWith("|") && i + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()) {
                flush(out, paragraph);
                out.append("<table border=\"1\" cellspacing=\"0\" cellpadding=\"3\"><tr>");
                for (String cell : cells(trimmed)) {
                    out.append("<th>").append(inline(cell)).append("</th>");
                }
                out.append("</tr>");
                i += 2;
                while (i < lines.length && lines[i].trim().startsWith("|")) {
                    out.append("<tr>");
                    for (String cell : cells(lines[i].trim())) {
                        out.append("<td>").append(inline(cell)).append("</td>");
                    }
                    out.append("</tr>");
                    i++;
                }
                out.append("</table>");
                continue;
            }
            if (UNORDERED.matcher(line).matches() || ORDERED.matcher(line).matches()) {
                flush(out, paragraph);
                boolean ordered = ORDERED.matcher(line).matches();
                Pattern item = ordered ? ORDERED : UNORDERED;
                out.append(ordered ? "<ol>" : "<ul>");
                while (i < lines.length) {
                    Matcher m = item.matcher(lines[i]);
                    if (m.matches()) {
                        out.append("<li>").append(inline(m.group(1)));
                        i++;
                        // Continuation lines and nested items are shown inside the same item.
                        while (i < lines.length && !lines[i].trim().isEmpty() && Character.isWhitespace(lines[i].charAt(0))) {
                            out.append("<br>").append(inline(lines[i].trim()));
                            i++;
                        }
                        out.append("</li>");
                    } else {
                        break;
                    }
                }
                out.append(ordered ? "</ol>" : "</ul>");
                continue;
            }
            paragraph.add(trimmed);
            i++;
        }
        flush(out, paragraph);
        return out.toString();
    }

    private static void flush(StringBuilder out, List<String> paragraph) {
        if (!paragraph.isEmpty()) {
            List<String> html = new ArrayList<>();
            for (String p : paragraph) {
                html.add(inline(p));
            }
            out.append("<p>").append(String.join("<br>", html)).append("</p>");
            paragraph.clear();
        }
    }

    private static List<String> cells(String row) {
        String r = row.trim();
        if (r.startsWith("|")) {
            r = r.substring(1);
        }
        if (r.endsWith("|")) {
            r = r.substring(0, r.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String c : r.split("(?<!\\\\)\\|", -1)) {
            cells.add(c.trim().replace("\\|", "|"));
        }
        return cells;
    }

    static String inline(String text) {
        Matcher m = INLINE.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            sb.append(escape(text.substring(last, m.start())));
            if (m.group(1) != null) {
                sb.append("<code>").append(escape(m.group(1))).append("</code>");
            } else if (m.group(2) != null || m.group(3) != null) {
                sb.append("<b>").append(inline(m.group(2) != null ? m.group(2) : m.group(3))).append("</b>");
            } else {
                sb.append("<i>").append(inline(m.group(4) != null ? m.group(4) : m.group(5))).append("</i>");
            }
            last = m.end();
        }
        sb.append(escape(text.substring(last)));
        return sb.toString();
    }

    static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '&': sb.append("&amp;"); break;
                case '"': sb.append("&quot;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }
}
