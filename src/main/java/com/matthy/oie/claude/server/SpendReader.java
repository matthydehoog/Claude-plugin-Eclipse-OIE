/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

/** Month-to-date spend from the Anthropic Admin API, implemented in the isolated engine. */
public interface SpendReader {

    /**
     * @param adminKey Admin API key of the organization
     * @param pluginApiKey the plugin's own API key, used to find the workspace it belongs to
     * @return JSON text: monthStart, currency, organizationUsd, and workspaceName/workspaceUsd when known
     */
    String read(String adminKey, String pluginApiKey) throws Exception;
}
