/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.migration.Migrator;

/**
 * Cleans up when the plugin is uninstalled: the saved settings (including the encrypted API keys)
 * and the saved conversations.
 *
 * OIE calls {@link #getUninstallStatements()} only when a user uninstalls the plugin, and runs the
 * statements at the next server start. OIE itself only removes the properties stored under the
 * plugin.xml name ("AI Assistant"), but the settings are saved under the plugin point
 * "Claude Assistant". The conversation files are not in the database, so they are deleted right
 * away, and the still-running plugin stops saving new ones.
 *
 * At every server start {@link #migrate()} renames role grants of the permissions that were
 * called "... Claude Assistant ..." before 2.0.0, in the table of the Role-Based Access Control
 * extension (if it is installed), so users keep what their role allowed.
 */
public class SettingsUninstaller extends Migrator {

    private static final Logger logger = LogManager.getLogger(SettingsUninstaller.class);

    static String deleteStatement() {
        return "DELETE FROM CONFIGURATION WHERE CATEGORY = '" + ClaudeServletInterface.PLUGIN_POINT.replace("'", "''") + "'";
    }

    /** Permission names before 2.0.0 and their new names. */
    static final Map<String, String> RENAMED_PERMISSIONS = new LinkedHashMap<>();
    static {
        RENAMED_PERMISSIONS.put("Use Claude Assistant", ClaudeServletInterface.PERMISSION_USE);
        RENAMED_PERMISSIONS.put("Run Claude Assistant actions", ClaudeServletInterface.PERMISSION_ACTIONS);
        RENAMED_PERMISSIONS.put("Manage Claude Assistant settings", ClaudeServletInterface.PERMISSION_SETTINGS);
    }

    /** The role grants of the Role-Based Access Control extension: (role_id, permission). */
    static final String RBAC_TABLE = "rbac_role_permission";

    @Override
    public void migrate() {
        try {
            int renamed = renamePermissions(getConnection());
            if (renamed > 0) {
                logger.info("AI Assistant: renamed " + renamed + " role grant(s) to the new permission names");
            }
        } catch (Exception e) {
            // Never stop the server for this; an administrator can grant the new names by hand.
            logger.warn("AI Assistant: could not rename the role grants of the old permission names", e);
        }
    }

    /**
     * Renames grants of the old permission names to the new ones. A role that already has the new
     * name loses only the old grant (no duplicate rows). Does nothing without the RBAC table.
     *
     * @return the number of grants renamed
     */
    static int renamePermissions(Connection connection) throws SQLException {
        if (connection == null || !tableExists(connection, RBAC_TABLE)) {
            return 0;
        }
        int renamed = 0;
        for (Map.Entry<String, String> name : RENAMED_PERMISSIONS.entrySet()) {
            // The derived table lets MySQL delete from the table it reads from.
            try (PreparedStatement duplicates = connection.prepareStatement("DELETE FROM " + RBAC_TABLE + " WHERE permission = ? AND role_id IN "
                    + "(SELECT role_id FROM (SELECT role_id FROM " + RBAC_TABLE + " WHERE permission = ?) granted)")) {
                duplicates.setString(1, name.getKey());
                duplicates.setString(2, name.getValue());
                duplicates.executeUpdate();
            }
            try (PreparedStatement rename = connection.prepareStatement("UPDATE " + RBAC_TABLE + " SET permission = ? WHERE permission = ?")) {
                rename.setString(1, name.getValue());
                rename.setString(2, name.getKey());
                renamed += rename.executeUpdate();
            }
        }
        if (!connection.getAutoCommit()) {
            connection.commit();
        }
        return renamed;
    }

    /** Table names are stored upper case (Derby, Oracle), lower case (PostgreSQL) or as written. */
    private static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String name : new String[] { table, table.toUpperCase(), table.toLowerCase() }) {
            try (ResultSet tables = meta.getTables(null, null, name, new String[] { "TABLE" })) {
                if (tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public void migrateSerializedData() {}

    @Override
    public List<String> getUninstallStatements() {
        removeConversations();
        return Collections.singletonList(deleteStatement());
    }

    private static void removeConversations() {
        try {
            ServicePlugin plugin = ControllerFactory.getFactory().createExtensionController().getServicePlugins().get(ClaudeServletInterface.PLUGIN_POINT);
            if (plugin instanceof ClaudeServicePlugin && ((ClaudeServicePlugin) plugin).service() != null) {
                ((ClaudeServicePlugin) plugin).service().uninstalled();
            }
        } catch (Exception e) {
            logger.warn("AI Assistant: could not stop saving conversations", e);
        }
        try {
            File dir = AssistantService.dataDir();
            deleteTree(dir.toPath());
            logger.info("AI Assistant: removed the saved conversations in " + dir);
        } catch (Exception e) {
            logger.warn("AI Assistant: could not remove the saved conversations", e);
        }
    }

    /** Deletes a folder and everything in it; a missing folder is fine. Links are removed, not followed. */
    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
