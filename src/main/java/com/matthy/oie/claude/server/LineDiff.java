/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;

/**
 * A line-based diff (longest common subsequence) for the approval dialog. The output is plain text
 * that both clients colour by the start of each line:
 *
 * <pre>
 * === label                 one block per changed script
 * &#64;&#64; line 12 &#64;&#64;             a hunk, with the line number in the new script
 *   unchanged line          up to {@link #CONTEXT} lines around each change; the rest is folded
 * - removed line
 * + added line
 * </pre>
 */
final class LineDiff {

    /** Text before the first block; the clients colour lines only from the first block on. */
    static final String BLOCK = "=== ";
    static final String HUNK = "@@ ";
    static final int CONTEXT = 3;
    /** Above this many LCS cells the changed middle is shown as removed and added as a whole. */
    static final long MAX_CELLS = 4_000_000L;

    private LineDiff() {
    }

    private static final class Op {
        final char kind; // ' ', '-', '+'
        final String line;
        final int newLine; // 1-based line in the new text that this op is at or before

        Op(char kind, String line, int newLine) {
            this.kind = kind;
            this.line = line;
            this.newLine = newLine;
        }
    }

    /** One block: the label and the changed lines with context, or "(no changes)". */
    static String block(String label, String oldText, String newText) {
        StringBuilder out = new StringBuilder(BLOCK).append(label).append('\n');
        List<Op> ops = ops(lines(oldText), lines(newText));
        boolean changed = false;
        for (Op op : ops) {
            changed |= op.kind != ' ';
        }
        if (!changed) {
            return out.append("(no changes)\n").toString();
        }
        // Mark the ops to show: every change and CONTEXT unchanged lines around it.
        boolean[] show = new boolean[ops.size()];
        for (int i = 0; i < ops.size(); i++) {
            if (ops.get(i).kind != ' ') {
                for (int j = Math.max(0, i - CONTEXT); j <= Math.min(ops.size() - 1, i + CONTEXT); j++) {
                    show[j] = true;
                }
            }
        }
        for (int i = 0; i < ops.size(); i++) {
            if (!show[i]) {
                continue;
            }
            if (i == 0 || !show[i - 1]) {
                out.append(HUNK).append("line ").append(ops.get(i).newLine).append(" @@\n");
            }
            Op op = ops.get(i);
            out.append(op.kind).append(' ').append(op.line).append('\n');
        }
        return out.toString();
    }

    /** A filter rule or transformer step, as far as the diff needs it. */
    static final class Element {
        final String name;
        /** Its serialized form without the sequence number: equal means unchanged. */
        final String key;
        /** The JavaScript OIE generates from it. */
        final String script;

        Element(String name, String key, String script) {
            this.name = name == null ? "" : name;
            this.key = key;
            this.script = script;
        }
    }

    /**
     * One block per new, changed or removed element. An unchanged element (same key) is skipped,
     * also when it moved. A changed element is compared with the remaining old element of the same
     * name, else with the remaining old element at the same position.
     *
     * @param kind e.g. "transformer step "
     */
    static String elementBlocks(String kind, List<Element> old, List<Element> now) {
        boolean[] used = new boolean[old.size()];
        boolean[] same = new boolean[now.size()];
        for (int i = 0; i < now.size(); i++) {
            for (int o = 0; o < old.size(); o++) {
                if (!used[o] && old.get(o).key.equals(now.get(i).key)) {
                    used[o] = true;
                    same[i] = true;
                    break;
                }
            }
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < now.size(); i++) {
            if (same[i]) {
                continue;
            }
            Element e = now.get(i);
            int match = -1;
            for (int o = 0; o < old.size() && match < 0; o++) {
                if (!used[o] && old.get(o).name.equals(e.name)) {
                    match = o;
                }
            }
            if (match < 0 && i < old.size() && !used[i]) {
                match = i;
            }
            String label = kind + i + " '" + e.name + "'";
            if (match < 0) {
                out.append(block(label + " (new)", null, e.script));
            } else {
                used[match] = true;
                String oldName = old.get(match).name;
                String was = oldName.equals(e.name) ? (match == i ? "" : " (was " + match + ")") : " (was " + match + " '" + oldName + "')";
                out.append(block(label + was, old.get(match).script, e.script));
            }
        }
        for (int o = 0; o < old.size(); o++) {
            if (!used[o]) {
                out.append(block(kind + o + " '" + old.get(o).name + "' (removed)", old.get(o).script, null));
            }
        }
        return out.length() == 0 ? "No " + kind.trim() + " is new, changed or removed; only the order differs.\n" : out.toString();
    }

    /** Lines of a text; null and "" have none. */
    static List<String> lines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String t = text.replace("\r\n", "\n").replace('\r', '\n');
        if (t.endsWith("\n")) {
            t = t.substring(0, t.length() - 1);
        }
        for (String line : t.split("\n", -1)) {
            out.add(line);
        }
        return out;
    }

    private static List<Op> ops(List<String> a, List<String> b) {
        int start = 0;
        while (start < a.size() && start < b.size() && a.get(start).equals(b.get(start))) {
            start++;
        }
        int endA = a.size(), endB = b.size();
        while (endA > start && endB > start && a.get(endA - 1).equals(b.get(endB - 1))) {
            endA--;
            endB--;
        }
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < start; i++) {
            ops.add(new Op(' ', a.get(i), i + 1));
        }
        int n = endA - start, m = endB - start;
        if ((long) n * m > MAX_CELLS) {
            for (int i = start; i < endA; i++) {
                ops.add(new Op('-', a.get(i), start + 1));
            }
            for (int j = start; j < endB; j++) {
                ops.add(new Op('+', b.get(j), j + 1));
            }
        } else {
            // lcs[i][j]: length of the LCS of a[start+i..endA) and b[start+j..endB).
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    lcs[i][j] = a.get(start + i).equals(b.get(start + j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            int i = 0, j = 0;
            while (i < n || j < m) {
                if (i < n && j < m && a.get(start + i).equals(b.get(start + j))) {
                    ops.add(new Op(' ', a.get(start + i), start + j + 1));
                    i++;
                    j++;
                } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                    // Removed lines before added ones, as in a unified diff.
                    ops.add(new Op('-', a.get(start + i), start + j + 1));
                    i++;
                } else {
                    ops.add(new Op('+', b.get(start + j), start + j + 1));
                    j++;
                }
            }
        }
        for (int k = 0; k < a.size() - endA; k++) {
            ops.add(new Op(' ', a.get(endA + k), endB + k + 1));
        }
        return ops;
    }
}
