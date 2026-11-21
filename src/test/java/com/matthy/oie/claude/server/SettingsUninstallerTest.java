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
    public void renamesTheRoleGrantsOfTheOldPermissionNames() throws Exception {
        try (java.sql.Connection db = java.sql.DriverManager.getConnection("jdbc:h2:mem:rbac;MODE=MySQL")) {
            try (java.sql.Statement s = db.createStatement()) {
                s.execute("CREATE TABLE rbac_role_permission (role_id INT, permission VARCHAR(255))");
                s.execute("INSERT INTO rbac_role_permission VALUES (1, 'Use Claude Assistant'), (1, 'Run Claude Assistant actions'), (1, 'viewChannels'),"
                        + " (2, 'Use Claude Assistant'), (2, 'Use AI Assistant'), (3, 'Manage Claude Assistant settings')");
            }

            // Role 2 already had the new name: only its old grant goes.
            assertEquals(3, SettingsUninstaller.renamePermissions(db));

            assertEquals("1 Run AI Assistant actions|1 Use AI Assistant|1 viewChannels|2 Use AI Assistant|3 Manage AI Assistant settings", grants(db));
            assertEquals(0, SettingsUninstaller.renamePermissions(db));
        }
    }

    @Test
    public void withoutTheRbacTableNothingHappens() throws Exception {
        try (java.sql.Connection db = java.sql.DriverManager.getConnection("jdbc:h2:mem:plain")) {
            assertEquals(0, SettingsUninstaller.renamePermissions(db));
        }
        assertEquals(0, SettingsUninstaller.renamePermissions(null));
    }

    private static String grants(java.sql.Connection db) throws Exception {
        StringBuilder out = new StringBuilder();
        try (java.sql.ResultSet r = db.createStatement().executeQuery("SELECT role_id, permission FROM rbac_role_permission ORDER BY role_id, permission")) {
            while (r.next()) {
                out.append(out.length() > 0 ? "|" : "").append(r.getInt(1)).append(' ').append(r.getString(2));
            }
        }
        return out.toString();
    }

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
