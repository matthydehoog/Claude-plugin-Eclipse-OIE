/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

/**
 * Sends the two requests of Test connection to the model provider: the model list and one short
 * request with one tool ({@link ConnectionTest#PROMPT}). No OIE data is sent. What the answers mean
 * is decided on the host side by {@link ConnectionTest#report}.
 */
public interface ConnectionTester {

    /**
     * @return JSON text {server, list, tool}. list and tool are each {error} when the server could
     *         not be reached, or {status, body?}; a successful list adds models (array of IDs), a
     *         successful tool request adds toolCalled and text. tool is absent when the list showed
     *         the server unreachable or the key refused.
     */
    String probe(String apiKey, String model) throws Exception;
}
