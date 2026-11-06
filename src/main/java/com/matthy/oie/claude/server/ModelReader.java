/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

/** The models an API key can use, from the configured provider, implemented in the isolated engine. */
public interface ModelReader {

    /**
     * @param apiKey the plugin's API key
     * @param provider the explicitly selected provider ("anthropic" or "openai-compatible")
     * @return JSON text containing the provider's models
     */
    String list(String apiKey, String baseUrl, String provider) throws Exception;
}
