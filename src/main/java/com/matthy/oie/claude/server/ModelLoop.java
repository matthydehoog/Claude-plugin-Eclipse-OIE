package com.matthy.oie.claude.server;

/**
 * The model/tool loop, implemented in the isolated engine (see {@link EngineLoader}). Only JDK and
 * host-side types cross this boundary, never SDK or Jackson types.
 */
public interface ModelLoop extends AutoCloseable {

    /** Answers one user message: calls Claude and tools until done. Blocks; records events on the job. */
    void run(ChatJob job, String userText);

    @Override
    void close();

    /** A conversation's history as JSON, for saving it; null when there is none yet. */
    String saveHistory(Object history) throws Exception;

    /** The history object for a saved conversation, from {@link #saveHistory}. */
    Object loadHistory(String json) throws Exception;

    /** Runs a tool on the host side and returns the text for Claude. Throws to report a tool error. */
    interface ToolCaller {
        String call(ChatJob job, String name, String inputJson) throws Exception;
    }
}
