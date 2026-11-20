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
 * away, and the still-running plugin stops saving new ones. There is nothing to migrate.
 */
public class SettingsUninstaller extends Migrator {

    private static final Logger logger = LogManager.getLogger(SettingsUninstaller.class);

    static String deleteStatement() {
        return "DELETE FROM CONFIGURATION WHERE CATEGORY = '" + ClaudeServletInterface.PLUGIN_POINT.replace("'", "''") + "'";
    }

    @Override
    public void migrate() {}

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
