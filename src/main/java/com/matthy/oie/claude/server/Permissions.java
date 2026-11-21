/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_ACTIONS;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_GROUP;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_SETTINGS;
import static com.matthy.oie.claude.shared.ClaudeServletInterface.PERMISSION_USE;

import java.util.function.Function;

import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.core.api.util.OperationUtil;
import com.mirth.connect.model.ExtensionPermission;

/** The plugin's three permissions, grouped under {@link ClaudeServletInterface#PERMISSION_GROUP}. */
final class Permissions {

    private Permissions() {}

    static ExtensionPermission[] all() {
        return all(permission -> OperationUtil.getOperationNamesForPermission(permission, ClaudeServletInterface.class));
    }

    /** With the operation names of each permission from {@code operations} (tests do without OIE's reflection). */
    static ExtensionPermission[] all(Function<String, String[]> operations) {
        return new ExtensionPermission[] {
                new ExtensionPermission(PERMISSION_GROUP, PERMISSION_USE, "Chat with the AI Assistant about channels, messages and server status (read only).",
                        operations.apply(PERMISSION_USE), ClaudeServletInterface.USE_TASKS),
                new ExtensionPermission(PERMISSION_GROUP, PERMISSION_ACTIONS, "Approve actions the AI Assistant proposes: deploy, start/stop, reprocess, send messages, change scripts.",
                        operations.apply(PERMISSION_ACTIONS), new String[] {}),
                new ExtensionPermission(PERMISSION_GROUP, PERMISSION_SETTINGS, "Change the Anthropic API key, model and masking settings.",
                        operations.apply(PERMISSION_SETTINGS), ClaudeServletInterface.SETTINGS_TASKS) };
    }
}
