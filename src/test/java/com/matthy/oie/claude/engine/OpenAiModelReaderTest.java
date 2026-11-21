/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenAiModelReaderTest {

    @Test
    public void aWrongBaseUrlNamesTheAddressAndHintsAtV1() {
        // "/vi" instead of "/v1": endpoint() does not see a version, adds /v1, and the server answers 404.
        String url = OpenAiCompatibleLoop.endpoint("https://api.openai.com/vi", "/models");
        assertEquals("https://api.openai.com/vi/v1/models", url);

        String message = OpenAiModelReader.failure(404, url);

        assertTrue(message, message.contains("https://api.openai.com/vi/v1/models"));
        assertTrue(message, message.contains("Check the API base URL"));
        assertTrue(message, message.contains("/v1"));
    }

    @Test
    public void otherStatusesKeepTheirMessage() {
        assertTrue(OpenAiModelReader.failure(401, "x").contains("did not accept the API key"));
        assertEquals("Loading the model list from https://h/v1/models failed (HTTP 500).", OpenAiModelReader.failure(500, "https://h/v1/models"));
    }
}
