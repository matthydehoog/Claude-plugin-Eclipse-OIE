/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

/** The models an API key can use, from Anthropic's Models API, implemented in the isolated engine. */
public interface ModelReader {

    /**
     * @param apiKey the plugin's API key
     * @return JSON text: an array of objects with id, displayName and createdAt (ISO 8601), in the API's order
     */
    String list(String apiKey) throws Exception;
}
