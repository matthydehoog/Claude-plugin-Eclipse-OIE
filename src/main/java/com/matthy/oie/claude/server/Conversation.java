package com.matthy.oie.claude.server;

import java.util.UUID;

/** One chat, kept in server memory and owned by one Administrator user. */
public class Conversation {

    final String id = UUID.randomUUID().toString();
    final int userId;
    volatile long lastUsed = System.currentTimeMillis();
    /** The job currently running for this conversation, if any. Guarded by this object. */
    ChatJob activeJob;
    /** Message history in the engine's own (SDK) types; opaque on this side of the class loader boundary. */
    private Object history;

    Conversation(int userId) {
        this.userId = userId;
    }

    public synchronized Object history() {
        return history;
    }

    public synchronized void setHistory(Object history) {
        this.history = history;
    }
}
