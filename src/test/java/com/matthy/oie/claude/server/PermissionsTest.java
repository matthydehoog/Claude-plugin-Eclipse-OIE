/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

import com.matthy.oie.claude.client.ClaudeClientPlugin;
import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.core.api.MirthOperation;

/**
 * What an RBAC extension needs to enforce the plugin's permissions: every REST operation has one of
 * the three permissions, and every Administrator task that opens the chat is published with "Use
 * AI Assistant" (unpublished tasks cannot be hidden).
 */
public class PermissionsTest {

    @Test
    public void everyOperationHasAPluginPermission() {
        List<String> permissions = Arrays.asList(ClaudeServletInterface.PERMISSION_USE, ClaudeServletInterface.PERMISSION_ACTIONS, ClaudeServletInterface.PERMISSION_SETTINGS);
        int operations = 0;
        for (Method m : ClaudeServletInterface.class.getMethods()) {
            MirthOperation op = m.getAnnotation(MirthOperation.class);
            if (op != null) {
                operations++;
                assertTrue(op.name() + " has permission " + op.permission(), permissions.contains(op.permission()));
            }
        }
        assertEquals(12, operations);
    }

    @Test
    public void everyChatTaskIsPublishedWithUse() {
        // Task callbacks are public no-argument claude* methods, called by name by the Administrator.
        Set<String> callbacks = new TreeSet<>();
        for (Method m : ClaudeClientPlugin.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getParameterCount() == 0 && m.getName().startsWith("claude")) {
                callbacks.add(m.getName());
            }
        }
        assertEquals(callbacks, new TreeSet<>(Arrays.asList(ClaudeServletInterface.USE_TASKS)));
    }

    @Test
    public void settingsTabTasksMatchTheAdministratorTaskGroup() {
        assertEquals(Arrays.asList("settings_AI Assistant/doRefresh", "settings_AI Assistant/doSave"), Arrays.asList(ClaudeServletInterface.SETTINGS_TASKS));
    }

    @Test
    public void permissionsAndOperationsShareTheGroupName() {
        // RBAC maps an operation to its permission by "<extension name>#<operation>": the permissions
        // must use the same extension name as the servlet (PERMISSION_GROUP).
        com.mirth.connect.model.ExtensionPermission[] permissions = Permissions.all(permission -> new String[0]);
        assertEquals(3, permissions.length);
        for (com.mirth.connect.model.ExtensionPermission p : permissions) {
            assertEquals(p.getDisplayName(), ClaudeServletInterface.PERMISSION_GROUP, p.getExtensionName());
        }
        assertEquals("AI Assistant", ClaudeServletInterface.PERMISSION_GROUP);
    }

    @Test
    public void actionCheckUsesTheConfirmOperation() throws Exception {
        MirthOperation op = ClaudeServletInterface.class.getMethod("confirm", String.class, String.class).getAnnotation(MirthOperation.class);
        assertEquals(AssistantService.CONFIRM_OPERATION, op.name());
        assertEquals(ClaudeServletInterface.PERMISSION_ACTIONS, op.permission());
    }
}
