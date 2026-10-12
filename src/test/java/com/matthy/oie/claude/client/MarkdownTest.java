/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.client;

import static org.junit.Assert.assertEquals;

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
}
