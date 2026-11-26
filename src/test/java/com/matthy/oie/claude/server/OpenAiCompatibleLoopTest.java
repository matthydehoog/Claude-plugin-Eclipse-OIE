/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.matthy.oie.claude.engine.OpenAiCompatibleLoop;
import com.matthy.oie.claude.engine.OpenAiModelReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/** The OpenAI-compatible loop and model reader against a small fake Chat Completions server. */
public class OpenAiCompatibleLoopTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    private final List<String> authorization = Collections.synchronizedList(new ArrayList<>());
    private final List<String> responses = Collections.synchronizedList(new ArrayList<>());
    private int status = 200;

    @Before
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpHandler completion = exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                requests.add(MAPPER.readTree(in));
            }
            paths.add(exchange.getRequestURI().getPath());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, status, responses.isEmpty() ? "{}" : responses.remove(0));
        };
        server.createContext("/v1/chat/completions", completion);
        server.createContext("/v1/responses", completion);
        server.createContext("/v1/models", exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, status, "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-old\",\"created\":1600000000},{\"id\":\"gpt-new\",\"created\":1700000000},{\"id\":\"local\"}]}");
        });
        server.start();
    }

    @After
    public void stop() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private static List<Map<String, Object>> tools() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("name", "oie_list_channels");
        spec.put("description", "Lists the channels.");
        spec.put("properties", Collections.singletonMap("filter", Collections.singletonMap("type", "string")));
        spec.put("required", Collections.emptyList());
        return Collections.singletonList(spec);
    }

    private static List<String> events(ChatJob job, String type) {
        List<String> texts = new ArrayList<>();
        for (JsonNode event : job.toJson(0).path("events")) {
            if (type.equals(event.path("type").asText())) {
                texts.add(event.path("text").asText());
            }
        }
        return texts;
    }

    @Test
    public void callsAToolAndAnswersWithTheResult() throws Exception {
        responses.add("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":null,\"refusal\":null,\"annotations\":[],"
                + "\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"oie_list_channels\",\"arguments\":\"{\\\"filter\\\":\\\"ADT\\\"}\"}}]}}]}");
        responses.add("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Two ADT channels.\",\"refusal\":null}}]}");
        List<String> toolInputs = new ArrayList<>();
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("sk-test", baseUrl(), "gpt-test", 25, "You help with OIE.", tools(),
                (job, name, input) -> {
                    toolInputs.add(name + " " + input);
                    return "ADT in, ADT out";
                });
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "Which ADT channels are there?");

        assertEquals(ChatJob.State.DONE, job.state());
        assertEquals(List.of("Two ADT channels."), events(job, "text"));
        assertEquals(List.of("oie_list_channels {\"filter\":\"ADT\"}"), toolInputs);
        assertEquals("Bearer sk-test", authorization.get(0));

        JsonNode first = requests.get(0);
        assertEquals("gpt-test", first.path("model").asText());
        assertEquals("/v1/chat/completions", paths.get(0));
        assertFalse(first.has("store"));
        assertFalse(first.has("reasoning_effort"));
        assertEquals("system", first.path("messages").get(0).path("role").asText());
        assertEquals("oie_list_channels", first.path("tools").get(0).path("function").path("name").asText());
        assertEquals("object", first.path("tools").get(0).path("function").path("parameters").path("type").asText());

        // The second request carries the assistant's tool call (without response-only fields) and the tool result.
        JsonNode messages = requests.get(1).path("messages");
        JsonNode call = messages.get(messages.size() - 2);
        assertEquals("assistant", call.path("role").asText());
        assertFalse(call.has("refusal"));
        assertFalse(call.has("annotations"));
        assertEquals("call_1", call.path("tool_calls").get(0).path("id").asText());
        JsonNode result = messages.get(messages.size() - 1);
        assertEquals("tool", result.path("role").asText());
        assertEquals("call_1", result.path("tool_call_id").asText());
        assertEquals("ADT in, ADT out", result.path("content").asText());
    }

    @Test
    public void responsesApiCallsAToolAndReplaysEveryOutputItem() throws Exception {
        String output = "[{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[],\"encrypted_content\":\"synthetic-reasoning\"},"
                + "{\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\",\"call_id\":\"call_1\","
                + "\"name\":\"oie_list_channels\",\"arguments\":\"{\\\"filter\\\":\\\"ADT\\\"}\"}]";
        responses.add("{\"status\":\"completed\",\"output\":" + output + "}");
        responses.add("{\"status\":\"completed\",\"output\":[{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Two ADT channels.\",\"annotations\":[]}]}]}");
        List<String> toolInputs = new ArrayList<>();
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("sk-test", baseUrl(), "custom-reasoning-model", true, 25, "You help with OIE.", tools(),
                (job, name, input) -> {
                    toolInputs.add(name + " " + input);
                    return "ADT in, ADT out";
                });
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "Which ADT channels are there?");

        assertEquals(ChatJob.State.DONE, job.state());
        assertEquals(List.of("Two ADT channels."), events(job, "text"));
        assertEquals(List.of("oie_list_channels {\"filter\":\"ADT\"}"), toolInputs);
        assertEquals(List.of("/v1/responses", "/v1/responses"), paths);
        assertEquals("Bearer sk-test", authorization.get(0));
        JsonNode first = requests.get(0);
        assertEquals("custom-reasoning-model", first.path("model").asText());
        assertTrue(first.has("store"));
        assertFalse(first.path("store").asBoolean());
        assertFalse(first.has("messages"));
        assertFalse(first.has("reasoning_effort"));
        assertEquals("system", first.path("input").path(0).path("role").asText());
        JsonNode function = first.path("tools").path(0);
        assertEquals("function", function.path("type").asText());
        assertEquals("oie_list_channels", function.path("name").asText());
        assertFalse(function.has("function"));
        assertTrue(function.has("strict"));
        assertFalse(function.path("strict").asBoolean());

        JsonNode input = requests.get(1).path("input");
        assertEquals(5, input.size());
        assertEquals(MAPPER.readTree(output).get(0), input.get(2));
        assertEquals(MAPPER.readTree(output).get(1), input.get(3));
        assertEquals("function_call_output", input.get(4).path("type").asText());
        assertEquals("call_1", input.get(4).path("call_id").asText());
        assertEquals("ADT in, ADT out", input.get(4).path("output").asText());
    }

    @Test
    public void responsesHistorySurvivesReloadWithoutMixingChatCompletions() throws Exception {
        responses.add("{\"status\":\"completed\",\"output\":[{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[],"
                + "\"encrypted_content\":\"synthetic-reasoning\"},{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"First answer.\",\"annotations\":[]}]}]}");
        responses.add("{\"status\":\"completed\",\"output\":[{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"Second answer.\",\"annotations\":[]}]}]}");
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "gpt-6.2-sol", true, 25, "System.", tools(), (job, name, input) -> "");
        Conversation conversation = new Conversation(1);
        loop.run(new ChatJob(conversation, "127.0.0.1"), "First question");
        String saved = loop.saveHistory(conversation.history());
        OpenAiCompatibleLoop reloaded = new OpenAiCompatibleLoop("", baseUrl(), "gpt-6.2-sol", true, 25, "System.", tools(), (job, name, input) -> "");
        Conversation resumed = new Conversation(1);
        resumed.setHistory(reloaded.loadHistory(saved));
        ChatJob followUp = new ChatJob(resumed, "127.0.0.1");

        reloaded.run(followUp, "Second question");

        assertEquals(ChatJob.State.DONE, followUp.state());
        JsonNode input = requests.get(1).path("input");
        assertEquals(5, input.size());
        assertEquals("synthetic-reasoning", input.get(2).path("encrypted_content").asText());
        assertEquals("First answer.", input.get(3).path("content").path(0).path("text").asText());
        assertEquals("Second question", input.get(4).path("content").asText());
        assertNull(reloaded.loadHistory("{\"openAiMessages\":[{\"role\":\"user\",\"content\":\"Chat history\"}]}"));
        OpenAiCompatibleLoop chat = new OpenAiCompatibleLoop("", baseUrl(), "local", 25, "System.", tools(), (job, name, arguments) -> "");
        assertNull(chat.loadHistory(saved));
    }

    @Test
    public void responsesApiOmitsToolsAfterTheToolLimit() throws Exception {
        responses.add("{\"status\":\"completed\",\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_1\","
                + "\"name\":\"oie_list_channels\",\"arguments\":\"{}\"}]}");
        responses.add("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"Done.\"}]}]}");
        List<String> called = new ArrayList<>();
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "gpt-6.2-sol", true, 1, "System.", tools(),
                (job, name, input) -> {
                    called.add(name);
                    return "One channel.";
                });
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "List channels");

        assertEquals(ChatJob.State.DONE, job.state());
        assertEquals(List.of("oie_list_channels"), called);
        assertEquals(2, requests.size());
        assertFalse(requests.get(1).has("tools"));
        assertEquals("function_call_output", requests.get(1).path("input").path(3).path("type").asText());
    }

    @Test
    public void changingApiModeStartsANewModelContextInBothDirections() throws Exception {
        responses.add("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Chat answer.\"}}]}");
        responses.add("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"Responses answer.\"}]}]}");
        responses.add("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"New chat answer.\"}}]}");
        OpenAiCompatibleLoop chat = new OpenAiCompatibleLoop("", baseUrl(), "gpt-6.2-sol", 25, "System.", tools(), (job, name, input) -> "");
        OpenAiCompatibleLoop responsesLoop = new OpenAiCompatibleLoop("", baseUrl(), "custom-model", true, 25, "System.", tools(), (job, name, input) -> "");
        Conversation conversation = new Conversation(1);
        ChatJob first = new ChatJob(conversation, "127.0.0.1");
        ChatJob second = new ChatJob(conversation, "127.0.0.1");
        ChatJob third = new ChatJob(conversation, "127.0.0.1");

        chat.run(first, "First question");
        responsesLoop.run(second, "Second question");
        chat.run(third, "Third question");

        assertEquals(ChatJob.State.DONE, first.state());
        assertEquals(ChatJob.State.DONE, second.state());
        assertEquals(ChatJob.State.DONE, third.state());
        assertEquals(List.of("/v1/chat/completions", "/v1/responses", "/v1/chat/completions"), paths);
        assertEquals(2, requests.get(1).path("input").size());
        assertEquals("Second question", requests.get(1).path("input").path(1).path("content").asText());
        assertEquals(2, requests.get(2).path("messages").size());
        assertEquals("Third question", requests.get(2).path("messages").path(1).path("content").asText());
    }

    @Test
    public void responsesApiReportsTruncatedText() throws Exception {
        responses.add("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Partial answer.\"}]}]}");
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "gpt-6.2-sol", true, 25, "System.", tools(), (job, name, input) -> "");
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "Hello");

        assertEquals(ChatJob.State.DONE, job.state());
        assertEquals(List.of("Partial answer."), events(job, "text"));
        assertTrue(events(job, "info").get(0).contains("cut off"));
    }

    @Test
    public void responsesApiNeverExecutesIncompleteToolCalls() throws Exception {
        responses.add("{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"oie_list_channels\",\"arguments\":\"{\"}]}");
        List<String> called = new ArrayList<>();
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "gpt-6.2-sol", true, 25, "System.", tools(),
                (job, name, input) -> {
                    called.add(name);
                    return "";
                });
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "List channels");

        assertEquals(ChatJob.State.ERROR, job.state());
        assertTrue(called.isEmpty());
        assertEquals(1, requests.size());
        assertTrue(events(job, "error").get(0).contains("incomplete"));
    }

    @Test
    public void responsesApiReportsFailedResponsesEvenWithHttp200() throws Exception {
        responses.add("{\"status\":\"failed\",\"error\":{\"message\":\"Synthetic model failure\"},\"output\":[]}");
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "gpt-6.2-sol", true, 25, "System.", tools(), (job, name, input) -> "");
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "Hello");

        assertEquals(ChatJob.State.ERROR, job.state());
        assertTrue(events(job, "error").get(0).contains("Synthetic model failure"));
        assertEquals(1, requests.size());
    }

    @Test
    public void aFollowUpQuestionSendsTheEarlierConversation() throws Exception {
        responses.add("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"First answer.\"}}]}");
        responses.add("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"Second answer.\"}}]}");
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "local", 25, "System.", tools(), (job, name, input) -> "");
        Conversation conversation = new Conversation(1);

        loop.run(new ChatJob(conversation, "127.0.0.1"), "First question");
        loop.run(new ChatJob(conversation, "127.0.0.1"), "Second question");

        JsonNode messages = requests.get(1).path("messages");
        assertEquals(4, messages.size()); // system, user, assistant, user
        assertEquals("First answer.", messages.get(2).path("content").asText());
        assertEquals("Second question", messages.get(3).path("content").asText());
        assertNull("no API key, no Authorization header", authorization.get(0));
    }

    @Test
    public void aToolErrorGoesBackToTheModelInsteadOfEndingTheAnswer() throws Exception {
        responses.add("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"oie_list_channels\",\"arguments\":\"\"}}]}}]}");
        responses.add("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"The lookup failed.\"}}]}");
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "local", 25, "System.", tools(), (job, name, input) -> {
            throw new IllegalStateException("You may not view channels.");
        });
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "List channels");

        assertEquals(ChatJob.State.DONE, job.state());
        JsonNode messages = requests.get(1).path("messages");
        assertEquals("Error: You may not view channels.", messages.get(messages.size() - 1).path("content").asText());
    }

    @Test
    public void anHttpErrorGivesAReadableMessage() throws Exception {
        status = 401;
        responses.add("{\"error\":{\"message\":\"Incorrect API key provided\"}}");
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("bad", baseUrl(), "gpt-test", 25, "System.", tools(), (job, name, input) -> "");
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "Hello");

        assertEquals(ChatJob.State.ERROR, job.state());
        assertTrue(events(job, "error").get(0), events(job, "error").get(0).contains("did not accept the API key (HTTP 401)"));
    }

    @Test
    public void anUnreachableServerSaysSo() throws Exception {
        String url = baseUrl();
        server.stop(0);
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", url, "local", 25, "System.", tools(), (job, name, input) -> "");
        ChatJob job = new ChatJob(new Conversation(1), "127.0.0.1");

        loop.run(job, "Hello");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); // for stop()

        assertEquals(ChatJob.State.ERROR, job.state());
        assertTrue(events(job, "error").get(0), events(job, "error").get(0).startsWith("Cannot reach the model server"));
    }

    @Test
    public void savedHistoryIsKeptApartFromClaudes() throws Exception {
        OpenAiCompatibleLoop loop = new OpenAiCompatibleLoop("", baseUrl(), "local", 25, "System.", tools(), (job, name, input) -> "");
        Object history = MAPPER.readTree("{\"openAiMessages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}");
        String saved = loop.saveHistory(history);

        assertEquals("Hi", ((JsonNode) loop.loadHistory(saved)).path("openAiMessages").get(0).path("content").asText());
        // A conversation saved with Claude ({"messages": [...]}) continues without that context.
        assertNull(loop.loadHistory("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"));
    }

    @Test
    public void modelListIsReadNewestFirst() throws Exception {
        JsonNode models = MAPPER.readTree(new OpenAiModelReader(baseUrl()).list("sk-test"));

        assertEquals("Bearer sk-test", authorization.get(0));
        JsonNode list = ModelList.build(models, null, "gpt-new", 1_000L, true);
        assertEquals("openai-compatible", list.path("source").asText());
        assertEquals("gpt-new", list.path("models").get(0).path("id").asText());
        assertEquals("gpt-old", list.path("models").get(1).path("id").asText());
        assertEquals("local", list.path("models").get(2).path("id").asText());
        assertFalse(list.has("warning"));
    }

    @Test
    public void modelListErrorIsReadable() {
        status = 401;
        try {
            new OpenAiModelReader(baseUrl()).list("bad");
        } catch (Exception e) {
            assertTrue(e.getMessage(), e.getMessage().contains("HTTP 401"));
            return;
        }
        throw new AssertionError("expected an error");
    }
}
