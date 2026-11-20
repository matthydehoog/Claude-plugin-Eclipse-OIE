/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SettingsUninstallerTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void uninstallRemovesTheSettingsSavedUnderThePluginPoint() {
        assertEquals("DELETE FROM CONFIGURATION WHERE CATEGORY = 'Claude Assistant'", SettingsUninstaller.deleteStatement());
    }

    @Test
    public void uninstallRemovesTheConversationFolder() throws Exception {
        Path data = temp.newFolder("claude-assistant").toPath();
        Path user = Files.createDirectories(data.resolve("conversations").resolve("1"));
        Files.write(user.resolve("0b1c2d3e-0000-0000-0000-000000000000.json"), "{}".getBytes());
        File sibling = temp.newFile("other-plugin.txt");

        SettingsUninstaller.deleteTree(data);

        assertFalse(Files.exists(data));
        assertTrue("nothing outside the plugin folder is touched", sibling.exists());
        SettingsUninstaller.deleteTree(data); // already gone: no error
    }
}
