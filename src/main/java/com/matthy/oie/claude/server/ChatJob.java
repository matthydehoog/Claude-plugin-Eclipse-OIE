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
        events.add(new Event(events.size() + 1, type, text));
    }

    synchronized State state() {
        return state;
    }

    public synchronized void finish(State state) {
        if (this.state == State.RUNNING || this.state == State.WAITING) {
            this.state = state;
        }
        pending = null;
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
        synchronized (this) {
            p = pending;
            f = future;
        }
        if (p != null) {
            p.decision.complete(p.review ? null : "The user stopped the conversation; the action was not run.");
        }
        if (f != null) {
            f.cancel(true);
        }
        finish(State.CANCELLED);
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
