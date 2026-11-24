/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class LineDiffTest {

    private static String numbered(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append("line ").append(i).append('\n');
        }
        return sb.toString();
    }

    @Test
    public void aChangedLineInALongScriptShowsOnlyThatLineWithContext() {
        String old = numbered(1, 100);
        String now = old.replace("line 50\n", "line fifty\n");
        assertEquals("=== deploy script\n"
                + "@@ line 47 @@\n"
                + "  line 47\n  line 48\n  line 49\n"
                + "- line 50\n"
                + "+ line fifty\n"
                + "  line 51\n  line 52\n  line 53\n", LineDiff.block("deploy script", old, now));
    }

    @Test
    public void addedAndRemovedLinesAndSeparateHunks() {
        String old = numbered(1, 20);
        String now = "line 0\n" + old.replace("line 15\n", "");
        assertEquals("=== s\n"
                + "@@ line 1 @@\n"
                + "+ line 0\n"
                + "  line 1\n  line 2\n  line 3\n"
                + "@@ line 13 @@\n"
                + "  line 12\n  line 13\n  line 14\n"
                + "- line 15\n"
                + "  line 16\n  line 17\n  line 18\n", LineDiff.block("s", old, now));
    }

    @Test
    public void nearbyChangesShareOneHunk() {
        String old = "a\nb\nc\nd\ne\n";
        String now = "a\nB\nc\nD\ne\n";
        String diff = LineDiff.block("s", old, now);
        assertEquals(1, diff.split("@@ ", -1).length - 1);
        assertEquals("=== s\n@@ line 1 @@\n  a\n- b\n+ B\n  c\n- d\n+ D\n  e\n", diff);
    }

    @Test
    public void newEmptyAndUnchangedScripts() {
        assertEquals("=== s\n@@ line 1 @@\n+ x\n+ y\n", LineDiff.block("s", null, "x\ny"));
        assertEquals("=== s\n@@ line 1 @@\n- x\n", LineDiff.block("s", "x", ""));
        assertEquals("=== s\n(no changes)\n", LineDiff.block("s", "x\r\ny\n", "x\ny"));
    }

    @Test
    public void aVeryLargeChangeIsShownAsRemovedAndAdded() {
        StringBuilder a = new StringBuilder(), b = new StringBuilder();
        for (int i = 0; i < 2100; i++) {
            a.append("a").append(i).append('\n');
            b.append("b").append(i).append('\n');
        }
        String diff = LineDiff.block("s", a.toString(), b.toString());
        assertTrue(diff.contains("- a0\n") && diff.contains("+ b2099\n"));
        assertFalse(diff.contains("\n  "));
    }

    private static LineDiff.Element el(String name, String script) {
        return new LineDiff.Element(name, "<step><name>" + name + "</name><script>" + script + "</script></step>", script);
    }

    @Test
    public void elementsAreComparedByNameAndUnchangedOnesAreSkipped() {
        List<LineDiff.Element> old = List.of(el("mrn", "var a = 1;"), el("name", "var n = 1;"), el("old", "x();"));
        List<LineDiff.Element> now = List.of(el("name", "var n = 2;"), el("mrn", "var a = 1;"), el("added", "y();"), el("more", "z();"));
        String diff = LineDiff.elementBlocks("transformer step ", old, now);
        assertEquals("=== transformer step 0 'name' (was 1)\n@@ line 1 @@\n- var n = 1;\n+ var n = 2;\n"
                + "=== transformer step 2 'added' (was 2 'old')\n@@ line 1 @@\n- x();\n+ y();\n"
                + "=== transformer step 3 'more' (new)\n@@ line 1 @@\n+ z();\n", diff);
        String removed = LineDiff.elementBlocks("transformer step ", old, List.of(old.get(0)));
        assertEquals("=== transformer step 1 'name' (removed)\n@@ line 1 @@\n- var n = 1;\n"
                + "=== transformer step 2 'old' (removed)\n@@ line 1 @@\n- x();\n", removed);
    }

    @Test
    public void aRenamedElementIsComparedWithTheOneAtItsPosition() {
        String diff = LineDiff.elementBlocks("filter rule ", List.of(el("a", "return true;")), List.of(el("b", "return false;")));
        assertEquals("=== filter rule 0 'b' (was 0 'a')\n@@ line 1 @@\n- return true;\n+ return false;\n", diff);
    }

    @Test
    public void onlyTheOrderChanged() {
        List<LineDiff.Element> old = new ArrayList<>(List.of(el("a", "1"), el("b", "2")));
        assertEquals("No filter rule is new, changed or removed; only the order differs.\n",
                LineDiff.elementBlocks("filter rule ", old, List.of(old.get(1), old.get(0))));
    }
}
