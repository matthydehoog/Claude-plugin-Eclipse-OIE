package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One chat, kept in server memory and owned by one Administrator user. */
public class Conversation {

    /** One line of the readable log: a question, an answer, a tool call, an action result or a notice. */
    static final class Entry {
        /** question, text (answer), tool, action, info or error; the same types as the job events. */
        final String type;
        final String text;

        Entry(String type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    final String id;
    final int userId;
    final long created;
    volatile long lastUsed = System.currentTimeMillis();
    /** The job currently running for this conversation, if any. Guarded by this object. */
    ChatJob activeJob;
    /** Message history in the engine's own (SDK) types; opaque on this side of the class loader boundary. */
    private Object history;
    /** What the user saw, for export and for showing a continued conversation. Everything in it went to Claude masked. */
    private final List<Entry> log = new ArrayList<>();

    Conversation(int userId) {
        this(UUID.randomUUID().toString(), userId, System.currentTimeMillis());
    }

    /** A conversation continued from storage keeps its ID. */
    Conversation(String id, int userId, long created) {
        this.id = id;
        this.userId = userId;
        this.created = created;
    }

    public synchronized Object history() {
        return history;
    }

    public synchronized void setHistory(Object history) {
        this.history = history;
    }

    synchronized void record(String type, String text) {
        log.add(new Entry(type, text == null ? "" : text));
    }

    synchronized List<Entry> log() {
        return new ArrayList<>(log);
    }

    /** The first question, shortened: the title in the list of saved conversations. */
    synchronized String title() {
        for (Entry e : log) {
            if (e.type.equals("question")) {
                String line = e.text.strip().replaceAll("\\s+", " ");
                return line.length() > 80 ? line.substring(0, 77) + "…" : line;
            }
        }
        return "(no question)";
    }
}
