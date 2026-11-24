/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.engine;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.Timeout;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.anthropic.models.models.ModelInfo;
import com.anthropic.models.models.ModelListParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.matthy.oie.claude.server.ConnectionTest;
import com.matthy.oie.claude.server.ConnectionTester;

/** Test connection for Anthropic: the Models API, then one short Messages request with one tool. */
public final class SdkConnectionTester implements ConnectionTester {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Timeout TIMEOUT = Timeout.builder()
            .connect(Duration.ofSeconds(10))
            .read(Duration.ofSeconds(60))
            .write(Duration.ofSeconds(30))
            .request(Duration.ofSeconds(60))
            .build();

    @Override
    public String probe(String apiKey, String model) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        // Jackson and Kotlin reflection look classes up through the context class loader.
        thread.setContextClassLoader(SdkConnectionTester.class.getClassLoader());
        // No retries: the test should show the first answer, not wait out a back-off.
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(TIMEOUT).maxRetries(0);
        String server = "api.anthropic.com";
        String baseUrl = System.getProperty("oie.claude.baseUrl");
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.trim());
            try {
                server = URI.create(baseUrl.trim()).getHost();
            } catch (IllegalArgumentException e) {
                server = baseUrl.trim();
            }
        }
        AnthropicClient client = builder.build();
        try {
            ObjectNode out = MAPPER.createObjectNode().put("server", server);
            ObjectNode list = out.putObject("list");
            try {
                ArrayNode ids = MAPPER.createArrayNode();
                for (ModelInfo info : client.models().list(ModelListParams.builder().limit(100L).build()).data()) {
                    ids.add(info.id());
                }
                list.put("status", 200).set("models", ids);
            } catch (AnthropicServiceException e) {
                list.put("status", e.statusCode()).put("body", message(e));
            } catch (AnthropicIoException e) {
                list.put("error", SdkModelLoop.unreachable(e));
                return out.toString();
            }
            int status = list.path("status").asInt();
            if (status == 401 || status == 403 || model == null || model.isBlank()) {
                return out.toString();
            }

            BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
            for (Map.Entry<String, Object> p : ConnectionTest.TOOL_PROPERTIES.entrySet()) {
                props.putAdditionalProperty(p.getKey(), JsonValue.from(p.getValue()));
            }
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(1024)
                    .addUserMessage(ConnectionTest.PROMPT)
                    .addTool(BetaTool.builder().name(ConnectionTest.TOOL_NAME).description(ConnectionTest.TOOL_DESCRIPTION)
                            .inputSchema(BetaTool.InputSchema.builder().properties(props.build()).required(List.of("status")).build()).build())
                    .build();
            ObjectNode tool = out.putObject("tool");
            try {
                BetaMessage response = client.beta().messages().create(params);
                boolean called = false;
                StringBuilder text = new StringBuilder();
                for (BetaContentBlock block : response.content()) {
                    if (block.isToolUse() && ConnectionTest.TOOL_NAME.equals(block.asToolUse().name())) {
                        called = true;
                    } else if (block.isText()) {
                        text.append(block.asText().text());
                    }
                }
                tool.put("status", 200).put("toolCalled", called).put("text", text.toString());
            } catch (AnthropicServiceException e) {
                tool.put("status", e.statusCode()).put("body", message(e));
            } catch (AnthropicIoException e) {
                tool.put("error", SdkModelLoop.unreachable(e));
            }
            return out.toString();
        } finally {
            client.close();
            thread.setContextClassLoader(previous);
        }
    }

    /** The API's own error message, with its type (e.g. not_found_error) so the host can classify it. */
    private static String message(AnthropicServiceException e) {
        String type = e.errorType().map(Object::toString).orElse("");
        String text = e.apiMessage().orElse(e.getMessage() == null ? "" : e.getMessage());
        return type.isEmpty() ? text : type + ": " + text;
    }
}
