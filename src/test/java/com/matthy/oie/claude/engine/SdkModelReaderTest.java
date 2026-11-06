/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.engine;

import static org.junit.Assert.assertEquals;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

public class SdkModelReaderTest {

    @Test
    public void anthropicProxyUsesAnthropicAuthenticationWithoutDomainDetection() throws Exception {
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> version = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/proxy/v1/models", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] body = "{\"data\":[{\"id\":\"claude-proxy-model\",\"display_name\":\"Proxy Model\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/proxy";
            String response = new SdkModelReader().list("proxy-key", baseUrl, "anthropic");
            var json = new ObjectMapper().readTree(response);

            assertEquals("proxy-key", apiKey.get());
            assertEquals("2023-06-01", version.get());
            assertEquals("anthropic", json.path("source").asText());
            assertEquals("Proxy Model", json.path("models").get(0).path("displayName").asText());
        } finally {
            server.stop(0);
        }
    }
}
