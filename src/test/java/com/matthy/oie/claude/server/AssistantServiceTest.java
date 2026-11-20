/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.JsonNode;
import com.matthy.oie.claude.shared.ClaudeServletInterface;
import com.mirth.connect.client.core.ExtensionOperation;
import com.mirth.connect.client.core.Operation;
import com.mirth.connect.donkey.model.event.Event;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.server.controllers.AuthorizationController;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.server.controllers.EventController;

/**
 * The parts of {@link AssistantService} that protect data and control actions, with a scripted
 * model loop and Mockito fakes for the OIE controllers: the permission check per tool, the check
 * again at approval, review before sending, the audit events and cancelling.
 */
public class AssistantServiceTest {

    private static final int USER = 7;
    private static final String ADDRESS = "127.0.0.1";
    private static final long WAIT_MS = 5000;

    private ControllerFactory previousFactory;
    private AuthorizationController authorization;
    private EventController events;
    private ChannelController channels;
    private EngineController engine;

    /** Operations the user is denied, by OIE operation name. */
    private final Set<String> denied = Collections.synchronizedSet(new java.util.HashSet<>());
    /** Results the scripted model loop received for each tool call, in order. */
    private final List<String> toolResults = new CopyOnWriteArrayList<>();
    private final List<AssistantService> services = new ArrayList<>();

    @Before
    public void installFakeControllers() throws Exception {
        authorization = mock(AuthorizationController.class);
        events = mock(EventController.class);
        channels = mock(ChannelController.class);
        engine = mock(EngineController.class);
        ConfigurationController configuration = mock(ConfigurationController.class);
        when(configuration.getServerId()).thenReturn("server-1");
        when(authorization.isUserAuthorized(any(), any(Operation.class), anyMap(), any(), anyBoolean()))
                .thenAnswer(call -> !denied.contains(((Operation) call.getArgument(1)).getName()));

        ControllerFactory factory = mock(ControllerFactory.class);
        when(factory.createAuthorizationController()).thenReturn(authorization);
        when(factory.createEventController()).thenReturn(events);
        when(factory.createChannelController()).thenReturn(channels);
        when(factory.createEngineController()).thenReturn(engine);
        when(factory.createConfigurationController()).thenReturn(configuration);
        previousFactory = swapFactory(factory);
    }

    @After
    public void restoreFactory() throws Exception {
        for (AssistantService service : services) {
            service.shutdown();
        }
        swapFactory(previousFactory);
    }

    private static ControllerFactory swapFactory(ControllerFactory factory) throws Exception {
        Field field = ControllerFactory.class.getDeclaredField("factory");
        field.setAccessible(true);
        ControllerFactory previous = (ControllerFactory) field.get(null);
        field.set(null, factory);
        return previous;
    }

    /** A service whose model loop calls the given tools, one after another, then answers. */
    private AssistantService service(String review, String[]... calls) {
        Settings settings = new Settings("sk-test", "", null, null, 0, "", null, review, 0);
        AssistantService service = new AssistantService(settings, (s, prompt, tools, caller) -> new ModelLoop() {
            @Override
            public void run(ChatJob job, String userText) {
                for (String[] call : calls) {
                    if (job.isCancelled()) {
                        return;
                    }
                    try {
                        toolResults.add(caller.call(job, call[0], call[1]));
                    } catch (Exception e) {
                        toolResults.add("error: " + e.getMessage());
                    }
                }
                job.emit("text", "Done.");
            }

            @Override
            public void close() {
            }

            @Override
            public String saveHistory(Object history) {
                return null;
            }

            @Override
            public Object loadHistory(String json) {
                return null;
            }
        });
        services.add(service);
        return service;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > until) {
                fail("Timed out waiting for " + what);
            }
            Thread.sleep(10);
        }
    }

    private static boolean finished(ChatJob job) {
        ChatJob.State state = job.state();
        return state == ChatJob.State.DONE || state == ChatJob.State.ERROR || state == ChatJob.State.CANCELLED;
    }

    private static List<String> eventTexts(ChatJob job, String type) {
        List<String> texts = new ArrayList<>();
        for (JsonNode event : job.toJson(0).path("events")) {
            if (type.equals(event.path("type").asText())) {
                texts.add(event.path("text").asText());
            }
        }
        return texts;
    }

    private List<ServerEvent> dispatchedEvents() {
        ArgumentCaptor<Event> captor = ArgumentCaptor.forClass(Event.class);
        verify(events, org.mockito.Mockito.atLeast(0)).dispatchEvent(captor.capture());
        List<ServerEvent> serverEvents = new ArrayList<>();
        for (Event event : captor.getAllValues()) {
            serverEvents.add((ServerEvent) event);
        }
        return serverEvents;
    }

    private Channel channel() {
        Channel channel = new Channel("ch-1");
        channel.setName("ADT in");
        when(channels.getChannelById("ch-1")).thenReturn(channel);
        return channel;
    }

    /** How OIE names the plugin's confirm operation: extension point, "#", operation. */
    private static final String CONFIRM = ClaudeServletInterface.PLUGIN_POINT + "#" + AssistantService.CONFIRM_OPERATION;

    private static final String[] DEPLOY = { "oie_deploy_channel", "{\"channel\":\"ch-1\"}" };
    private static final String[] EVENTS = { "oie_events", "{}" };

    private void someServerEvents() throws Exception {
        ServerEvent event = new ServerEvent("server-1", "Channel deployed");
        event.setLevel(ServerEvent.Level.INFORMATION);
        event.setOutcome(ServerEvent.Outcome.SUCCESS);
        event.setAttributes(Map.of("note", "Deployed by the night shift"));
        when(events.getEvents(any(), anyInt(), anyInt())).thenReturn(List.of(event));
    }

    // ------------------------------------------------------------------ permissions

    @Test
    public void aToolTheUserMayNotUseIsSkippedWithoutRunning() throws Exception {
        denied.add("getEvents");
        AssistantService service = service(Settings.REVIEW_OFF, EVENTS);
        ChatJob job = service.startChat(USER, ADDRESS, null, "What happened?", null);
        await(() -> finished(job), "the chat");

        verify(events, never()).getEvents(any(), anyInt(), anyInt());
        assertTrue(toolResults.get(0).contains("does not have the OIE permission"));
        assertTrue(eventTexts(job, "info").get(0).startsWith("Skipped oie_events"));
    }

    @Test
    public void anActionIsNotProposedWithoutTheRunActionsPermission() throws Exception {
        channel();
        denied.add(CONFIRM);
        AssistantService service = service(Settings.REVIEW_OFF, DEPLOY);
        ChatJob job = service.startChat(USER, ADDRESS, null, "Deploy it", null);
        await(() -> finished(job), "the chat");

        assertNull("nothing waits for approval", job.pending());
        assertTrue(toolResults.get(0).contains(ClaudeServletInterface.PERMISSION_ACTIONS));
        verify(engine, never()).deployChannels(any(), any(), any(), any());
    }

    @Test
    public void anApprovedActionIsCheckedAgainForTheApprover() throws Exception {
        channel();
        AssistantService service = service(Settings.REVIEW_OFF, DEPLOY);
        ChatJob job = service.startChat(USER, ADDRESS, null, "Deploy it", null);
        await(() -> job.pending() != null, "the approval");

        // The role changed after Claude proposed the action.
        denied.add("deployChannels");
        String result = service.decide(job, job.pending().id, true, new ServerEventContext(USER), ADDRESS);
        await(() -> finished(job), "the chat");

        assertTrue(result.contains("does not have the OIE permission"));
        verify(engine, never()).deployChannels(any(), any(), any(), any());
        ServerEvent audit = dispatchedEvents().get(0);
        assertEquals(ServerEvent.Outcome.FAILURE, audit.getOutcome());
        assertEquals("Not authorized", audit.getAttributes().get("result"));
        assertEquals("oie_deploy_channel", audit.getAttributes().get("tool"));
    }

    @Test
    public void anApprovedActionRunsOnceAndIsAudited() throws Exception {
        channel();
        AssistantService service = service(Settings.REVIEW_OFF, DEPLOY);
        ChatJob job = service.startChat(USER, ADDRESS, null, "Deploy it", null);
        await(() -> job.pending() != null, "the approval");

        service.decide(job, job.pending().id, true, new ServerEventContext(USER), ADDRESS);
        await(() -> finished(job), "the chat");

        verify(engine, times(1)).deployChannels(any(), any(), any(), any());
        ServerEvent audit = dispatchedEvents().get(0);
        assertEquals(ServerEvent.Outcome.SUCCESS, audit.getOutcome());
        assertEquals(USER, (int) audit.getUserId());
        assertTrue(toolResults.get(0).contains("has been deployed"));
    }

    @Test
    public void aRejectedActionDoesNotRun() throws Exception {
        channel();
        AssistantService service = service(Settings.REVIEW_OFF, DEPLOY);
        ChatJob job = service.startChat(USER, ADDRESS, null, "Deploy it", null);
        await(() -> job.pending() != null, "the approval");

        service.decide(job, job.pending().id, false, new ServerEventContext(USER), ADDRESS);
        await(() -> finished(job), "the chat");

        verify(engine, never()).deployChannels(any(), any(), any(), any());
        assertTrue(toolResults.get(0).startsWith("The user rejected this action"));
    }

    // ------------------------------------------------------------------ review before sending

    @Test
    public void withheldDataIsNotSentAndNotAudited() throws Exception {
        someServerEvents();
        AssistantService service = service(Settings.REVIEW_DATA, EVENTS);
        ChatJob job = service.startChat(USER, ADDRESS, null, "What happened?", null);
        await(() -> job.pending() != null, "the review");
        assertTrue(job.pending().review);

        service.decideReview(job, job.pending().id, false, null);
        await(() -> finished(job), "the chat");

        assertTrue(toolResults.get(0).startsWith("The user reviewed this tool result and chose not to send it"));
        assertTrue(dispatchedEvents().isEmpty());
    }

    @Test
    public void editedDataIsMaskedAgainBeforeItIsSent() throws Exception {
        someServerEvents();
        AssistantService service = service(Settings.REVIEW_DATA, EVENTS);
        ChatJob job = service.startChat(USER, ADDRESS, null, "What happened?", null);
        await(() -> job.pending() != null, "the review");
        String shown = job.pending().prepared.detail;

        // The user pastes a patient segment back in while editing.
        service.decideReview(job, job.pending().id, true, shown + "\nPID|1||654321^^^HOSP^MR||Roe^Rick");
        await(() -> finished(job), "the chat");

        String sent = toolResults.get(0);
        assertFalse(sent.contains("Roe^Rick"));
        assertFalse(sent.contains("654321"));
        ServerEvent audit = dispatchedEvents().get(0);
        assertEquals("reviewed, edited", audit.getAttributes().get("review"));
    }

    @Test
    public void reviewingEveryQuestionCanWithholdTheQuestionItself() throws Exception {
        AssistantService service = service(Settings.REVIEW_ALL, EVENTS);
        ChatJob job = service.startChat(USER, ADDRESS, null, "What happened?", null);
        await(() -> job.pending() != null, "the review");
        assertEquals("question", job.pending().toolName);

        service.decideReview(job, job.pending().id, false, null);
        await(() -> finished(job), "the chat");

        assertEquals(ChatJob.State.CANCELLED, job.state());
        assertTrue("the model loop never ran", toolResults.isEmpty());
    }

    // ------------------------------------------------------------------ audit

    @Test
    public void theDataAuditRecordsWhatWentOutButNotTheContent() throws Exception {
        someServerEvents();
        AssistantService service = service(Settings.REVIEW_OFF, EVENTS);
        ChatJob job = service.startChat(USER, ADDRESS, null, "What happened?", null);
        await(() -> finished(job), "the chat");

        ServerEvent audit = dispatchedEvents().get(0);
        Map<String, String> attributes = audit.getAttributes();
        assertEquals("AI Assistant: data sent to Anthropic", audit.getName());
        assertEquals(USER, (int) audit.getUserId());
        assertEquals(ADDRESS, audit.getIpAddress());
        assertEquals("oie_events", attributes.get("tool"));
        assertEquals("not reviewed", attributes.get("review"));
        assertEquals(job.conversation().id, attributes.get("conversation"));
        assertNotNull(attributes.get("characters"));
        for (String value : attributes.values()) {
            assertFalse("no content in the audit: " + value, value.contains("Channel deployed") || value.contains("night shift"));
        }
    }

    // ------------------------------------------------------------------ cancelling

    @Test
    public void cancellingWhileAnActionWaitsRunsNothing() throws Exception {
        channel();
        AssistantService service = service(Settings.REVIEW_OFF, DEPLOY, DEPLOY);
        ChatJob job = service.startChat(USER, ADDRESS, null, "Deploy it twice", null);
        await(() -> job.pending() != null, "the approval");

        job.cancel();
        await(() -> finished(job), "the chat");

        assertEquals(ChatJob.State.CANCELLED, job.state());
        verify(engine, never()).deployChannels(any(), any(), any(), any());
        assertTrue(dispatchedEvents().isEmpty());
    }

    @Test
    public void aJobBelongsToItsUser() {
        AssistantService service = service(Settings.REVIEW_OFF);
        ChatJob job = service.startChat(USER, ADDRESS, null, "Hello", null);
        try {
            service.job(job.id, USER + 1);
            fail("another user's job");
        } catch (IllegalArgumentException expected) {
            assertEquals(job, service.job(job.id, USER));
        }
    }

    /** The operation the action check uses is the plugin's confirm operation. */
    @Test
    public void runActionsIsCheckedAsTheConfirmExtensionOperation() throws Exception {
        channel();
        AssistantService service = service(Settings.REVIEW_OFF, DEPLOY);
        ChatJob job = service.startChat(USER, ADDRESS, null, "Deploy it", null);
        await(() -> job.pending() != null, "the approval");
        job.cancel();

        ArgumentCaptor<Operation> operations = ArgumentCaptor.forClass(Operation.class);
        verify(authorization, org.mockito.Mockito.atLeastOnce())
                .isUserAuthorized(any(), operations.capture(), anyMap(), anyString(), anyBoolean());
        assertTrue(operations.getAllValues().stream()
                .anyMatch(op -> op instanceof ExtensionOperation && CONFIRM.equals(op.getName())));
    }
}
