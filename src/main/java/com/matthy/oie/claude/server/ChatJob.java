/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One user turn: runs the model/tool loop in the background and records events the client polls
 * for. When Claude proposes an action, or when data is about to be sent that the user wants to
 * review first, the job waits until the user decides.
 */
public class ChatJob {

    /** Proposals whose scripts did not compile and were sent back to the model once. */
    private final java.util.Set<String> scriptRejections = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** True the first time a proposal with this key fails the compile check in this job. */
    boolean firstScriptRejection(String key) {
        return scriptRejections.add(key);
    }

    public enum State {
        RUNNING, WAITING, DONE, ERROR, CANCELLED
    }

    /**
     * An action, or outgoing data under review, waiting for the user's decision. The job thread
     * blocks on {@link #decision}. For a review, the detail is the exact text Claude would receive
     * and the prepared action has no runner.
     */
    static final class PendingAction {
        final String id = UUID.randomUUID().toString();
        final String toolName;
        final OieTools.PreparedAction prepared;
        final boolean review;
        /** Completed with the text for Claude once the user decided; for a review, null means "not sent". */
        final CompletableFuture<String> decision = new CompletableFuture<>();

        PendingAction(String toolName, OieTools.PreparedAction prepared, boolean review) {
            this.toolName = toolName;
            this.prepared = prepared;
            this.review = review;
        }
    }

    private static final class Event {
        final int seq;
        final String type;
        final String text;

        Event(int seq, String type, String text) {
            this.seq = seq;
            this.type = type;
            this.text = text;
        }
    }

    final String id = UUID.randomUUID().toString();
    final Conversation conversation;
    /** IP address of the user's client, for authorization checks and audit events. */
    final String address;

    public Conversation conversation() {
        return conversation;
    }

    private final List<Event> events = new ArrayList<>();
    /** Claude's answer while it is being written; replaced by the "text" event once it is complete. */
    private final StringBuilder partial = new StringBuilder();
    private State state = State.RUNNING;
    private PendingAction pending;
    private volatile boolean cancelled;
    private Future<?> future;

    ChatJob(Conversation conversation, String address) {
        this.conversation = conversation;
        this.address = address;
    }

    synchronized void setFuture(Future<?> future) {
        this.future = future;
    }

    /** Event types: text (Claude's answer, markdown), tool (a tool call), action (result of an approved action), info, error. */
    public synchronized void emit(String type, String text) {
        if (type.equals("text")) {
            partial.setLength(0);
        }
        events.add(new Event(events.size() + 1, type, text));
        conversation.record(type, text);
    }

    /** Adds streamed answer text; clients show it growing until the complete "text" event arrives. */
    public synchronized void appendPartial(String text) {
        partial.append(text);
    }

    /** Drops streamed text that will not be completed (a stopped or failed request). */
    public synchronized void clearPartial() {
        partial.setLength(0);
    }

    synchronized State state() {
        return state;
    }

    public synchronized void finish(State state) {
        if (this.state == State.RUNNING || this.state == State.WAITING) {
            this.state = state;
        }
        pending = null;
        partial.setLength(0);
    }

    synchronized PendingAction await(String toolName, OieTools.PreparedAction prepared, boolean review) {
        pending = new PendingAction(toolName, prepared, review);
        state = State.WAITING;
        return pending;
    }

    synchronized void resumed() {
        pending = null;
        if (state == State.WAITING) {
            state = State.RUNNING;
        }
    }

    synchronized PendingAction pending() {
        return pending;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    void cancel() {
        cancelled = true;
        PendingAction p;
        Future<?> f;
        // Set the final state before waking the worker: once the waiting action has its answer, the
        // worker may reach its own finish(DONE) first, which must then be a no-op.
        synchronized (this) {
            p = pending;
            f = future;
            finish(State.CANCELLED);
        }
        if (p != null) {
            p.decision.complete(p.review ? null : "The user stopped the conversation; the action was not run.");
        }
        if (f != null) {
            f.cancel(true);
        }
    }

    synchronized ObjectNode toJson(int after) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("jobId", id);
        node.put("conversationId", conversation.id);
        node.put("state", state.name());
        ArrayNode list = node.putArray("events");
        for (Event e : events) {
            if (e.seq > after) {
                ObjectNode en = list.addObject();
                en.put("seq", e.seq);
                en.put("type", e.type);
                en.put("text", e.text);
            }
        }
        if (partial.length() > 0) {
            node.put("partial", partial.toString());
        }
        if (pending != null) {
            // Separate keys, so an older client never shows data for review as an action to run.
            ObjectNode pa = node.putObject(pending.review ? "pendingReview" : "pendingAction");
            pa.put("id", pending.id);
            pa.put("tool", pending.toolName);
            pa.put("title", pending.prepared.title);
            pa.put("detail", pending.prepared.detail);
        }
        return node;
    }
}
