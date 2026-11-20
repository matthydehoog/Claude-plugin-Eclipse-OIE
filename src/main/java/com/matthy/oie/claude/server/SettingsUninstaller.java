/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.Collections;
import java.util.List;

import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.server.migration.Migrator;

/**
 * Removes the saved settings (including the encrypted API keys) when the plugin is uninstalled.
 *
 * OIE itself only removes the properties stored under the plugin.xml name ("AI Assistant"), but the
 * settings are saved under the plugin point "Claude Assistant". OIE collects these statements when
 * the user uninstalls the plugin and runs them at the next server start. There is nothing to migrate.
 */
public class SettingsUninstaller extends Migrator {

    static String deleteStatement() {
        return "DELETE FROM CONFIGURATION WHERE CATEGORY = '" + ClaudeServletInterface.PLUGIN_POINT.replace("'", "''") + "'";
    }

    @Override
    public void migrate() {}

    @Override
    public void migrateSerializedData() {}

    @Override
    public List<String> getUninstallStatements() {
        return Collections.singletonList(deleteStatement());
    }
}
