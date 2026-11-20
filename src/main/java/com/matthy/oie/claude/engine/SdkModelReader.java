/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.engine;

import java.time.Duration;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.Timeout;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ModelListPage;
import com.anthropic.models.models.ModelListParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.matthy.oie.claude.server.ModelReader;

/** The models the plugin's API key can use, from the Models API (GET /v1/models). */
public class SdkModelReader implements ModelReader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A short list: it should not keep the settings screen waiting like an answer may. */
    private static final Timeout TIMEOUT = Timeout.builder()
            .connect(Duration.ofSeconds(10))
            .read(Duration.ofSeconds(30))
            .write(Duration.ofSeconds(30))
            .request(Duration.ofSeconds(30))
            .build();

    /** Far more than Anthropic has models; only guards against a paging loop. */
    private static final int MAX_PAGES = 10;

    @Override
    public String list(String apiKey) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        // Jackson and Kotlin reflection look classes up through the context class loader.
        thread.setContextClassLoader(SdkModelReader.class.getClassLoader());
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(TIMEOUT).maxRetries(SdkModelLoop.MAX_RETRIES);
        String baseUrl = System.getProperty("oie.claude.baseUrl");
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.trim());
        }
        AnthropicClient client = builder.build();
        try {
            ArrayNode out = MAPPER.createArrayNode();
            ModelListPage page = client.models().list(ModelListParams.builder().limit(100L).build());
            for (int pages = 1; ; pages++) {
                for (ModelInfo model : page.data()) {
                    out.addObject().put("id", model.id()).put("displayName", model.displayName()).put("createdAt", model.createdAt().toString());
                }
                if (!page.hasNextPage() || pages >= MAX_PAGES) {
                    break;
                }
                page = page.nextPage();
            }
            return out.toString();
        } catch (AnthropicServiceException e) {
            throw new IllegalStateException(describe(e.statusCode()));
        } catch (AnthropicIoException e) {
            throw new IllegalStateException(SdkModelLoop.unreachable(e));
        } finally {
            client.close();
            thread.setContextClassLoader(previous);
        }
    }

    private static String describe(int status) {
        if (status == 401) {
            return "Anthropic did not accept the API key (HTTP 401), so the model list could not be loaded.";
        }
        if (status == 403) {
            return "The API key may not list models (HTTP 403).";
        }
        return "Loading the model list from Anthropic failed (HTTP " + status + ").";
    }
}
