/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ConnectionTestTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode report(String probe, String model, boolean openAi) throws Exception {
        return ConnectionTest.report(MAPPER.readTree(probe.replace('\'', '"')), model, openAi);
    }

    private static String status(JsonNode report, int check) {
        return report.path("checks").path(check).path("status").asText();
    }

    private static String message(JsonNode report, int check) {
        return report.path("checks").path(check).path("message").asText();
    }

    @Test
    public void allThreeChecksPass() throws Exception {
        JsonNode r = report("{'server':'api.openai.com','list':{'status':200,'models':['gpt-x']},'tool':{'status':200,'toolCalled':true}}", "gpt-x", true);
        assertTrue(r.path("ok").asBoolean());
        assertEquals("Reachable", r.path("checks").path(0).path("label").asText());
        assertEquals("Key accepted", r.path("checks").path(1).path("label").asText());
        assertEquals("Model", r.path("checks").path(2).path("label").asText());
        assertEquals("api.openai.com accepted the API key.", message(r, 1));
        assertEquals("'gpt-x' answered and called the test tool, so it supports tool calling.", message(r, 2));
    }

    @Test
    public void anUnreachableServerSkipsTheOtherChecks() throws Exception {
        JsonNode r = report("{'server':'ollama','list':{'error':'Cannot reach the model server (ConnectException). Check the firewall or proxy.'}}", "llama3", true);
        assertFalse(r.path("ok").asBoolean());
        assertEquals("failed", status(r, 0));
        assertTrue(message(r, 0).contains("proxy"));
        assertEquals("skipped", status(r, 1));
        assertEquals("skipped", status(r, 2));
    }

    @Test
    public void aRefusedKeyIsReportedWithTheProvidersMessage() throws Exception {
        JsonNode r = report("{'server':'api.anthropic.com','list':{'status':401,'body':'authentication_error: invalid x-api-key'}}", "claude-x", false);
        assertEquals("ok", status(r, 0));
        assertEquals("failed", status(r, 1));
        assertEquals("api.anthropic.com did not accept the API key (HTTP 401): authentication_error: invalid x-api-key. Check the key under Settings > AI Assistant.", message(r, 1));
        assertEquals("skipped", status(r, 2));
    }

    @Test
    public void aModelWithoutToolCallingIsReportedAsSuch() throws Exception {
        // Ollama refuses tools for such a model.
        JsonNode r = report("{'server':'localhost','list':{'status':200,'models':['gemma']},'tool':{'status':400,'body':'{\\'error\\':{\\'message\\':\\'registry.ollama.ai/library/gemma does not support tools\\'}}'}}", "gemma", true);
        assertEquals("ok", status(r, 1));
        assertEquals("failed", status(r, 2));
        assertTrue(message(r, 2), message(r, 2).startsWith("'gemma' does not support tool calling, which the AI Assistant needs (HTTP 400): registry.ollama.ai/library/gemma does not support tools."));
        // A model that answers in text instead of calling the tool.
        r = report("{'server':'localhost','list':{'status':200,'models':['tiny']},'tool':{'status':200,'toolCalled':false,'text':'ok'}}", "tiny", true);
        assertEquals("failed", status(r, 2));
        assertTrue(message(r, 2).contains("probably does not support tool calling"));
    }

    @Test
    public void anUnknownModelPointsToTheList() throws Exception {
        JsonNode r = report("{'server':'api.mistral.ai','list':{'status':200,'models':['a','b']},'tool':{'status':404,'body':'{\\'message\\':\\'Invalid model: nope\\'}'}}", "nope", true);
        assertEquals("failed", status(r, 2));
        assertEquals("The model 'nope' was not found at api.mistral.ai (HTTP 404): Invalid model: nope. The server lists 2 models: use Refresh list and choose one.", message(r, 2));
    }

    @Test
    public void noCreditIsAKeyProblem() throws Exception {
        JsonNode r = report("{'server':'api.anthropic.com','list':{'status':200,'models':['m']},'tool':{'status':400,'body':'invalid_request_error: Your credit balance is too low to access the Anthropic API.'}}", "m", false);
        assertEquals("failed", status(r, 1));
        assertTrue(message(r, 1), message(r, 1).startsWith("The API key was accepted, but the account has no credit or quota left (HTTP 400)"));
        assertEquals("skipped", status(r, 2));
        r = report("{'server':'api.openai.com','list':{'status':200},'tool':{'status':429,'body':'{\\'error\\':{\\'code\\':\\'insufficient_quota\\',\\'message\\':\\'You exceeded your current quota.\\'}}'}}", "m", true);
        assertEquals("failed", status(r, 1));
    }

    @Test
    public void withoutAModelListTheTestRequestDecidesTheKey() throws Exception {
        JsonNode r = report("{'server':'gw','list':{'status':404,'body':'Not Found'},'tool':{'status':200,'toolCalled':true}}", "m", true);
        assertTrue(r.path("ok").asBoolean());
        assertEquals("gw accepted the API key (the model list was not available: HTTP 404).", message(r, 1));
        r = report("{'server':'gw','list':{'status':404,'body':'Not Found'},'tool':{'status':404,'body':'Not Found'}}", "m", true);
        assertEquals("warning", status(r, 1));
        assertTrue(message(r, 1).endsWith("Check the API base URL: it usually ends in /v1."));
    }

    @Test
    public void aModelThatIsNotListedButAnswersPasses() throws Exception {
        JsonNode r = report("{'server':'s','list':{'status':200,'models':['a']},'tool':{'status':200,'toolCalled':true}}", "alias", true);
        assertEquals("ok", status(r, 2));
        assertTrue(message(r, 2).endsWith("(It is not in the server's model list, but it answered.)"));
    }

    @Test
    public void busyIsAWarning() throws Exception {
        JsonNode r = report("{'server':'api.anthropic.com','list':{'status':200},'tool':{'status':529,'body':'overloaded_error: Overloaded'}}", "m", false);
        assertEquals("warning", status(r, 2));
        assertFalse(r.path("ok").asBoolean());
    }
}
