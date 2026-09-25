package com.matthy.oie.claude.server;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.server.controllers.ControllerFactory;

/** Conversations, jobs, tool calls and action approval. The model loop itself runs in the engine. */
public class AssistantService {

    private static final Logger logger = LogManager.getLogger(AssistantService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final long CONVERSATION_TTL_MS = TimeUnit.HOURS.toMillis(4);
    private static final long ACTION_TIMEOUT_MINUTES = 15;

    /**
     * Read tools whose results carry message content, log lines or event details. With review
     * setting "data" only these are shown to the user before they are sent; with "all" every tool.
     */
    private static final Map<String, String> DATA_TOOLS = Map.of(
            "oie_get_message", "Message content",
            "oie_search_messages", "Message search results",
            "oie_server_log", "Server log lines",
            "oie_events", "Server events");

    private static final String LANGUAGE_LINE = "{language}";

    private static final String SYSTEM_PROMPT = String.join("\n",
            "You are the Claude assistant inside the Administrator of Open Integration Engine (OIE, a fork of Mirth Connect).",
            "You help integration specialists with their OIE server: channels, messages, errors, scripts, code templates and server status.",
            LANGUAGE_LINE,
            "",
            "How to work:",
            "- Use the oie_ tools to look facts up instead of guessing. For a general question, start with oie_list_channels.",
            "- For errors: find the messages with status ERROR, group them by error message, read the channel configuration and point out the filter, transformer step or connector that fails. Give a concrete fix, with code when a script is involved.",
            "- Channel JavaScript runs in Rhino (ES5 with some ES6 extensions) with the Mirth API: msg, tmp, channelMap, globalMap, logger, router and so on.",
            "",
            "Actions (deploy, start/stop, reset statistics, reprocess a message, send a message, change a channel or global script):",
            "- Every action tool first puts your proposal to the user for approval. Explain in one sentence why you propose the action before you call the tool.",
            "- Only propose an action when the user's question needs it. Do not retry a rejected action.",
            "- Be extra careful with production channels (names containing PROD or a hospital); prefer proposing a test first.",
            "",
            "Privacy: message content, logs and error messages are masked before they are sent; patient fields (HL7 PID, GDT 3000-3107, Dutch BSN) appear as ***.",
            "Do not try to recover masked data and do not ask the user for patient data.",
            "The user may review tool results before they reach you: they can edit them or withhold them. Work with what you receive and say plainly what you could not check.",
            "",
            "Formatting: the chat renders Markdown (headings, bold, italics, lists, tables and code blocks). Keep tables narrow.");

    private final ExecutorService executor = Executors.newCachedThreadPool(daemonThreads("Claude Assistant job"));
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(daemonThreads("Claude Assistant housekeeping"));
    private final Map<String, Conversation> conversations = new ConcurrentHashMap<>();
    private final Map<String, ChatJob> jobs = new ConcurrentHashMap<>();

    private EngineLoader engine;
    private String engineError;

    private volatile Settings settings;
    private volatile Masker masker;
    private volatile OieTools tools;
    private volatile ModelLoop loop;

    /** Anthropic asks to poll the cost report at most about once a minute. */
    private static final long SPEND_CACHE_MS = TimeUnit.SECONDS.toMillis(60);
    private SpendReader spendReader;
    private String cachedSpend;
    private String cachedSpendKey;
    private long cachedSpendAt;

    public AssistantService(Settings settings) {
        try {
            engine = new EngineLoader();
        } catch (Exception e) {
            engineError = e.getMessage();
            logger.error("Claude Assistant: engine could not be loaded", e);
        }
        configure(settings);
        housekeeping.scheduleWithFixedDelay(this::expire, 10, 10, TimeUnit.MINUTES);
    }

    /** Applies new settings. Running jobs keep the loop they started with. */
    public synchronized void configure(Settings settings) {
        Masker newMasker = new Masker(settings.maskPatterns);
        OieTools newTools = new OieTools(newMasker);
        ModelLoop newLoop = null;
        if (engine != null && !settings.apiKey.isEmpty()) {
            List<Map<String, Object>> specs = new ArrayList<>();
            for (OieTools.Spec spec : newTools.specs()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", spec.name);
                m.put("description", spec.description + (spec.action ? " (ACTION: the user must approve this first.)" : ""));
                m.put("properties", spec.properties);
                m.put("required", spec.required);
                specs.add(m);
            }
            try {
                newLoop = engine.create(settings, systemPrompt(settings), specs, this::callTool);
            } catch (Exception e) {
                logger.error("Claude Assistant: could not create the model client", e);
                engineError = "Cannot start the Claude client: " + e;
            }
        }
        ModelLoop old = this.loop;
        this.settings = settings;
        this.masker = newMasker;
        this.tools = newTools;
        this.loop = newLoop;
        if (old != null) {
            // Let jobs that still use the old loop finish before closing its HTTP client.
            housekeeping.schedule(old::close, 15, TimeUnit.MINUTES);
        }
    }

    public Settings settings() {
        return settings;
    }

    /**
     * The system prompt for these settings. It only changes with the settings, so it stays
     * byte-identical within a conversation and the prompt cache keeps working.
     */
    static String systemPrompt(Settings settings) {
        String language = settings.automaticLanguage()
                ? "Answer in the user's language, concisely and concretely."
                : "Always answer in " + settings.responseLanguage + ", concisely and concretely, whatever language the question, the server data or earlier messages are in. Keep code, identifiers and quoted error messages as they are.";
        return SYSTEM_PROMPT.replace(LANGUAGE_LINE, language);
    }

    /**
     * Month-to-date spend as JSON text (see {@link SpendReader}), cached for a minute.
     *
     * @param refresh bypass the cache
     */
    public synchronized String spend(boolean refresh) throws Exception {
        Settings s = settings;
        if (s.adminApiKey.isEmpty()) {
            throw new IllegalStateException("No Admin API key is set. Spend this month needs an Admin API key (sk-ant-admin...).");
        }
        if (engine == null) {
            throw new IllegalStateException("The Claude assistant cannot start: " + engineError);
        }
        String cacheKey = s.adminApiKey + '\n' + s.apiKey;
        long now = System.currentTimeMillis();
        if (!refresh && cachedSpend != null && cacheKey.equals(cachedSpendKey) && now - cachedSpendAt < SPEND_CACHE_MS) {
            return cachedSpend;
        }
        if (spendReader == null) {
            spendReader = engine.createSpendReader();
        }
        cachedSpend = spendReader.read(s.adminApiKey, s.apiKey);
        cachedSpendKey = cacheKey;
        cachedSpendAt = now;
        return cachedSpend;
    }

    public void shutdown() {
        for (ChatJob job : jobs.values()) {
            job.cancel();
        }
        executor.shutdownNow();
        housekeeping.shutdownNow();
        ModelLoop l = loop;
        if (l != null) {
            l.close();
        }
    }

    // ------------------------------------------------------------------ API used by the servlet

    public ChatJob startChat(int userId, String conversationId, String message, String context) {
        ModelLoop l = loop;
        if (l == null) {
            if (engineError != null) {
                throw new IllegalStateException("The Claude assistant cannot start: " + engineError);
            }
            throw new IllegalStateException("No Anthropic API key is set yet. Enter one under Settings > Claude Assistant.");
        }
        Conversation conversation;
        if (conversationId == null || conversationId.isBlank()) {
            conversation = new Conversation(userId);
            conversations.put(conversation.id, conversation);
        } else {
            conversation = conversations.get(conversationId);
            if (conversation == null || conversation.userId != userId) {
                throw new IllegalArgumentException("Conversation not found or expired. Start a new conversation.");
            }
        }
        ChatJob job;
        synchronized (conversation) {
            if (conversation.activeJob != null && !isFinished(conversation.activeJob)) {
                throw new IllegalStateException("Claude is still working on your previous question.");
            }
            job = new ChatJob(conversation);
            conversation.activeJob = job;
            conversation.lastUsed = System.currentTimeMillis();
        }
        jobs.put(job.id, job);
        String builtText = buildUserText(message, context);
        boolean reviewQuestion = Settings.REVIEW_ALL.equals(settings.reviewBeforeSending);
        job.setFuture(executor.submit(() -> {
            try {
                String userText = builtText;
                if (reviewQuestion) {
                    userText = review(job, "question", "Your question", builtText);
                    if (userText == null) {
                        job.emit("info", "Your question was not sent to Claude.");
                        job.finish(ChatJob.State.CANCELLED);
                        return;
                    }
                }
                l.run(job, userText);
            } finally {
                job.finish(ChatJob.State.DONE); // no-op when the loop already set a final state
                conversation.lastUsed = System.currentTimeMillis();
            }
        }));
        return job;
    }

    public ChatJob job(String jobId, int userId) {
        ChatJob job = jobs.get(jobId);
        if (job == null || job.conversation.userId != userId) {
            throw new IllegalArgumentException("Job not found or expired.");
        }
        return job;
    }

    /** Runs (or rejects) the pending action on the servlet thread, as the approving user. */
    public String decide(ChatJob job, String actionId, boolean approved, ServerEventContext context) {
        ChatJob.PendingAction pending = job.pending();
        if (pending == null || pending.review || !pending.id.equals(actionId)) {
            throw new IllegalStateException("This action is no longer waiting for a decision.");
        }
        String result;
        if (!approved) {
            result = "The user rejected this action. Do not run it and do not propose it again unless the user asks for it.";
            job.emit("info", "Action rejected: " + pending.prepared.title);
        } else {
            try {
                result = pending.prepared.runner.run(context);
                job.emit("action", pending.prepared.title + ": " + result);
                audit(context, pending, ServerEvent.Outcome.SUCCESS, result);
            } catch (Exception e) {
                logger.warn("Claude Assistant action " + pending.toolName + " failed", e);
                result = "Failed: " + e.getMessage();
                job.emit("error", pending.prepared.title + " failed: " + e.getMessage());
                audit(context, pending, ServerEvent.Outcome.FAILURE, String.valueOf(e.getMessage()));
            }
        }
        job.resumed();
        pending.decision.complete(result);
        return result;
    }

    /**
     * Sends (possibly edited) or withholds the data under review. Edited text goes through the
     * masker again, so the user cannot accidentally put patient data back in.
     */
    public void decideReview(ChatJob job, String reviewId, boolean approved, String text) {
        ChatJob.PendingAction pending = job.pending();
        if (pending == null || !pending.review || !pending.id.equals(reviewId)) {
            throw new IllegalStateException("This data is no longer waiting for review.");
        }
        String result = null;
        if (approved) {
            String edited = text == null ? pending.prepared.detail : masker.mask(text);
            boolean changed = !edited.equals(pending.prepared.detail);
            result = edited;
            job.emit("info", "Sent to Claude after review" + (changed ? " (edited)" : "") + ": " + pending.prepared.title);
        } else {
            job.emit("info", "Not sent to Claude: " + pending.prepared.title);
        }
        job.resumed();
        pending.decision.complete(result);
    }

    // ------------------------------------------------------------------ tool calls from the engine

    private String callTool(ChatJob job, String name, String inputJson) throws Exception {
        JsonNode input = MAPPER.readTree(inputJson == null || inputJson.isBlank() ? "{}" : inputJson);
        if (input == null || !input.isObject()) {
            input = MAPPER.createObjectNode();
        }
        OieTools t = tools;
        job.emit("tool", name + " " + summarize(input));
        if (!t.isAction(name)) {
            String result = t.run(name, input);
            String review = settings.reviewBeforeSending;
            boolean data = DATA_TOOLS.containsKey(name);
            if (Settings.REVIEW_ALL.equals(review) || (Settings.REVIEW_DATA.equals(review) && data)) {
                String sent = review(job, name, data ? DATA_TOOLS.get(name) : "Result of " + name, result);
                return sent != null ? sent
                        : "The user reviewed this tool result and chose not to send it to you. Continue without it, say what you could not check, and do not call this tool again for the same data unless the user asks.";
            }
            return result;
        }
        OieTools.PreparedAction prepared = t.prepare(name, input);
        // The confirmation dialog shows the detail, so it goes through the masker like everything else.
        OieTools.PreparedAction shown = new OieTools.PreparedAction(prepared.title, masker.mask(prepared.detail), prepared.runner);
        ChatJob.PendingAction pending = job.await(name, shown, false);
        try {
            return pending.decision.get(ACTION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            job.resumed();
            job.emit("info", "No decision within " + ACTION_TIMEOUT_MINUTES + " minutes; the action was not run.");
            return "The user did not decide in time; the action was not run.";
        }
    }

    /**
     * Shows the exact (already masked) text Claude would receive and waits until the user sends it,
     * possibly edited, or withholds it. Returns the text to send, or null when nothing is sent.
     */
    private String review(ChatJob job, String name, String title, String text) {
        ChatJob.PendingAction pending = job.await(name, new OieTools.PreparedAction(title, text, null), true);
        try {
            return pending.decision.get(ACTION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            job.resumed();
            job.emit("info", "No review within " + ACTION_TIMEOUT_MINUTES + " minutes; nothing was sent: " + title);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private String buildUserText(String message, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Server time: ").append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))).append("]\n");
        if (context != null && !context.isBlank()) {
            sb.append("[Context from the Administrator: ").append(context.trim()).append("]\n");
        }
        sb.append('\n').append(message);
        return masker.mask(sb.toString());
    }

    private String summarize(JsonNode input) {
        String json = input.toString();
        if (json.equals("{}")) {
            return "";
        }
        json = masker.mask(json);
        return json.length() > 300 ? json.substring(0, 300) + "…}" : json;
    }

    private void audit(ServerEventContext context, ChatJob.PendingAction pending, ServerEvent.Outcome outcome, String result) {
        try {
            ServerEvent event = new ServerEvent(ControllerFactory.getFactory().createConfigurationController().getServerId(), "Claude Assistant: " + pending.prepared.title);
            event.setLevel(ServerEvent.Level.INFORMATION);
            event.setOutcome(outcome);
            if (context != null && context.getUserId() != null) {
                event.setUserId(context.getUserId());
            }
            Map<String, String> attributes = new HashMap<>();
            attributes.put("tool", pending.toolName);
            attributes.put("detail", pending.prepared.detail);
            attributes.put("result", result);
            event.setAttributes(attributes);
            ControllerFactory.getFactory().createEventController().dispatchEvent(event);
        } catch (Exception e) {
            logger.warn("Could not write Claude Assistant audit event", e);
        }
    }

    private static boolean isFinished(ChatJob job) {
        ChatJob.State s = job.state();
        return s == ChatJob.State.DONE || s == ChatJob.State.ERROR || s == ChatJob.State.CANCELLED;
    }

    private void expire() {
        long now = System.currentTimeMillis();
        conversations.values().removeIf(c -> {
            ChatJob active = c.activeJob;
            return now - c.lastUsed > CONVERSATION_TTL_MS && (active == null || isFinished(active));
        });
        jobs.values().removeIf(j -> !conversations.containsKey(j.conversation.id) || (isFinished(j) && j.conversation.activeJob != j));
    }

    private static ThreadFactory daemonThreads(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + " " + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
