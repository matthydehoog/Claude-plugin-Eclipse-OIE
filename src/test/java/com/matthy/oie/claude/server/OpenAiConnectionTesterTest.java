/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matthy.oie.claude.engine.OpenAiConnectionTester;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

public class OpenAiConnectionTesterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    private int modelsStatus = 200;
    private int chatStatus = 200;
    private String chatBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"report_test_result\",\"arguments\":\"{\\\"status\\\":\\\"ok\\\"}\"}}]}}]}";

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> reply(exchange, modelsStatus, modelsStatus == 200 ? "{\"data\":[{\"id\":\"llama3.1\"}]}" : "{\"error\":\"unauthorized\"}"));
        server.createContext("/v1/chat/completions", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                requests.add(MAPPER.readTree(in));
            }
            reply(exchange, chatStatus, chatBody);
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private JsonNode test(String model) throws Exception {
        String probe = new OpenAiConnectionTester("http://127.0.0.1:" + server.getAddress().getPort() + "/v1").probe("", model);
        return ConnectionTest.report(MAPPER.readTree(probe), model, true);
    }

    @Test
    public void sendsOnlyTheTestPromptWithOneTool() throws Exception {
        JsonNode report = test("llama3.1");
        assertTrue(report.toString(), report.path("ok").asBoolean());
        assertEquals(1, requests.size());
        JsonNode request = requests.get(0);
        assertEquals("llama3.1", request.path("model").asText());
        assertEquals(1, request.path("messages").size());
        assertEquals(ConnectionTest.PROMPT, request.path("messages").path(0).path("content").asText());
        assertEquals(1, request.path("tools").size());
        assertEquals("report_test_result", request.path("tools").path(0).path("function").path("name").asText());
    }

    @Test
    public void aRefusedKeyStopsBeforeTheTestRequest() throws Exception {
        modelsStatus = 401;
        JsonNode report = test("llama3.1");
        assertEquals("failed", report.path("checks").path(1).path("status").asText());
        assertTrue(report.path("checks").path(1).path("message").asText().contains("(HTTP 401): unauthorized."));
        assertTrue(requests.isEmpty());
    }

    @Test
    public void aModelThatRefusesToolsFails() throws Exception {
        chatStatus = 400;
        chatBody = "{\"error\":{\"message\":\"registry.ollama.ai/library/gemma:2b does not support tools\",\"type\":\"api_error\"}}";
        JsonNode report = test("gemma:2b");
        assertEquals("failed", report.path("checks").path(2).path("status").asText());
        assertTrue(report.path("checks").path(2).path("message").asText().startsWith("'gemma:2b' does not support tool calling"));
    }

    @Test
    public void aTextAnswerWithoutTheToolFails() throws Exception {
        chatBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
        JsonNode report = test("tiny");
        assertFalse(report.path("ok").asBoolean());
        assertEquals("failed", report.path("checks").path(2).path("status").asText());
    }

    @Test
    public void anUnreachableServerIsReported() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0)) {
            port = free.getLocalPort();
        }
        String probe = new OpenAiConnectionTester("http://127.0.0.1:" + port + "/v1").probe("", "m");
        JsonNode report = ConnectionTest.report(MAPPER.readTree(probe), "m", true);
        assertEquals("failed", report.path("checks").path(0).path("status").asText());
        assertTrue(report.path("checks").path(0).path("message").asText(), report.path("checks").path(0).path("message").asText().startsWith("Cannot reach the model server"));
    }

    @Test
    public void anInvalidBaseUrlIsReported() throws Exception {
        String probe = new OpenAiConnectionTester("ht tp://bad url").probe("", "m");
        JsonNode report = ConnectionTest.report(MAPPER.readTree(probe), "m", true);
        assertEquals("failed", report.path("checks").path(0).path("status").asText());
        assertTrue(report.path("checks").path(0).path("message").asText(), report.path("checks").path(0).path("message").asText().contains("is not a valid URL"));
    }
}
