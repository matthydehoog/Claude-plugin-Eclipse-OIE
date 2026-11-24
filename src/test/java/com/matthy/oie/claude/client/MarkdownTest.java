/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class MarkdownTest {

    @Test
    public void theFileNameIsTheFirstHeading() {
        assertEquals("OIE XC ORM HAGA v_2_8_5 - documentation.md",
                Markdown.fileName("Some intro\n# OIE XC ORM HAGA v_2_8_5: **documentation**\n\n## Purpose\n", "x.md"));
    }

    @Test
    public void forbiddenCharactersAndEdgesAreCleanedUp() {
        assertEquals("a - b - c.md", Markdown.fileName("# a/b:c?", "x.md"));
        assertEquals("Lab results.md", Markdown.fileName("# ...Lab results... ##", "x.md"));
    }

    @Test
    public void withoutAHeadingTheFallbackIsUsed() {
        assertEquals("x.md", Markdown.fileName("No heading\n## Only a level 2 heading", "x.md"));
        assertEquals("x.md", Markdown.fileName("# ...", "x.md"));
        assertEquals("x.md", Markdown.fileName(null, "x.md"));
    }

    @Test
    public void codeBlocksGetACopyLinkAndAreCollectedExactly() {
        List<String> blocks = new ArrayList<>();
        blocks.add("earlier answer");
        String html = Markdown.toHtml("Fix:\n\n```javascript\nif (a < b && c) {\n    return '<x>';\n}\n```\n\nDone.", blocks);
        // The block exactly as written: no fences, no language tag, no trailing newline, not escaped.
        assertEquals(List.of("earlier answer", "if (a < b && c) {\n    return '<x>';\n}"), blocks);
        assertTrue(html, html.contains("<div class=\"copy\"><a href=\"copy:1\">Copy</a></div><pre>if (a &lt; b &amp;&amp; c) {"));
    }

    @Test
    public void withoutAListThereIsNoCopyLink() {
        assertFalse(Markdown.toHtml("```\nx\n```").contains("copy:"));
    }
}
