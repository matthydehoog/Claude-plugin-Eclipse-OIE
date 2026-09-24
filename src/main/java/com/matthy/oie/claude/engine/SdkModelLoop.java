package com.matthy.oie.claude.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.Timeout;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaDiagnosticsParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.matthy.oie.claude.server.ChatJob;
import com.matthy.oie.claude.server.Conversation;
import com.matthy.oie.claude.server.ModelLoop;

/**
 * The model/tool loop on top of the Anthropic Java SDK. Loaded in the engine class loader; talks
 * to the host only through {@link ChatJob}, {@link Conversation} and {@link ModelLoop.ToolCaller}.
 */
public class SdkModelLoop implements ModelLoop {

    private static final long MAX_TOKENS = 16_000;

    /**
     * Answers can take minutes, so reads and the whole request get 10 minutes. Setting up the
     * connection gets 10 seconds: without internet (e.g. a firewall that drops packets) the user
     * sees the error in about half a minute instead of waiting several minutes.
     */
    static final Timeout TIMEOUT = Timeout.builder()
            .connect(Duration.ofSeconds(10))
            .read(Duration.ofMinutes(10))
            .write(Duration.ofMinutes(1))
            .request(Duration.ofMinutes(10))
            .build();
    /** Retries rate limits, overloads and dropped connections; each attempt waits at most TIMEOUT.connect to connect. */
    static final int MAX_RETRIES = 2;
    private static final Logger LOG = LogManager.getLogger(SdkModelLoop.class);

    /** Cleared when the API rejects the cache-diagnostics beta, so it is not sent again. */
    private volatile boolean diagnostics = true;

    private final AnthropicClient client;
    private final String model;
    private final String effort;
    private final int maxToolCalls;
    private final String systemPrompt;
    private final List<BetaTool> tools = new ArrayList<>();
    private final ToolCaller caller;

    /** @param toolSpecs maps with name, description, properties (JSON schema per property) and required */
    @SuppressWarnings("unchecked")
    public SdkModelLoop(String apiKey, String model, String effort, int maxToolCalls, String systemPrompt, List toolSpecs, ToolCaller caller) {
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(TIMEOUT).maxRetries(MAX_RETRIES);
        // Optional JVM option (-Doie.claude.baseUrl=...) for an API gateway in front of api.anthropic.com.
        String baseUrl = System.getProperty("oie.claude.baseUrl");
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.trim());
        }
        this.client = builder.build();
        this.model = model;
        this.effort = effort;
        this.maxToolCalls = maxToolCalls;
        this.systemPrompt = systemPrompt;
        this.caller = caller;
        for (Map<String, Object> spec : (List<Map<String, Object>>) toolSpecs) {
            BetaTool.InputSchema.Properties.Builder props = BetaTool.InputSchema.Properties.builder();
            for (Map.Entry<String, Object> p : ((Map<String, Object>) spec.get("properties")).entrySet()) {
                props.putAdditionalProperty(p.getKey(), JsonValue.from(p.getValue()));
            }
            tools.add(BetaTool.builder()
                    .name((String) spec.get("name"))
                    .description((String) spec.get("description"))
                    .inputSchema(BetaTool.InputSchema.builder().properties(props.build()).required((List<String>) spec.get("required")).build())
                    .build());
        }
    }

    @Override
    public void close() {
        client.close();
    }

    @Override
    public void run(ChatJob job, String userText) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        // Jackson and Kotlin reflection look classes up through the context class loader.
        thread.setContextClassLoader(SdkModelLoop.class.getClassLoader());
        try {
            loop(job, userText);
        } catch (Throwable t) {
            if (!job.isCancelled()) {
                LOG.warn("Claude Assistant job failed", t);
                job.emit("error", describe(t));
                job.finish(ChatJob.State.ERROR);
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private void loop(ChatJob job, String userText) throws Exception {
        State state = state(job.conversation());
        List<BetaMessageParam> history = state.messages;
        synchronized (history) {
            history.add(BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(userText).build());
        }
        int toolCalls = 0;
        int call = 0;
        boolean limitReached = false;
        while (!job.isCancelled()) {
            BetaMessage response = create(snapshot(history), state.lastMessageId);
            state.lastMessageId = response.id();
            logUsage(state, ++call, response);
            if (job.isCancelled()) {
                return;
            }
            BetaStopReason stop = response.stopReason().orElse(null);
            if (BetaStopReason.REFUSAL.equals(stop)) {
                String why = response.stopDetails().flatMap(d -> d.explanation()).orElse("");
                job.emit("error", "Claude declined this question." + (why.isEmpty() ? "" : " " + why));
                job.finish(ChatJob.State.ERROR);
                return;
            }

            List<BetaToolUseBlock> toolUses = new ArrayList<>();
            for (BetaContentBlock block : response.content()) {
                block.text().ifPresent(t -> {
                    if (!t.text().isBlank()) {
                        job.emit("text", t.text());
                    }
                });
                block.toolUse().ifPresent(toolUses::add);
            }

            if (toolUses.isEmpty()) {
                synchronized (history) {
                    history.add(response.toParam());
                }
                if (BetaStopReason.MAX_TOKENS.equals(stop)) {
                    job.emit("info", "The answer was cut off because it got too long. Ask for a continuation if needed.");
                }
                job.finish(ChatJob.State.DONE);
                return;
            }

            // Every tool_use needs a tool_result in the next user message, so collect them all first.
            List<BetaContentBlockParam> results = new ArrayList<>();
            for (BetaToolUseBlock use : toolUses) {
                String result;
                boolean isError = false;
                if (limitReached || job.isCancelled()) {
                    result = "Not run: the limit of " + maxToolCalls + " tool calls per question has been reached. Answer now with the information you have.";
                    isError = true;
                } else {
                    toolCalls++;
                    try {
                        result = caller.call(job, use.name(), use._input().convert(com.fasterxml.jackson.databind.JsonNode.class).toString());
                    } catch (Exception e) {
                        result = e.getMessage() == null ? e.toString() : e.getMessage();
                        isError = true;
                    }
                }
                results.add(BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder().toolUseId(use.id()).content(result).isError(isError).build()));
            }
            synchronized (history) {
                history.add(response.toParam());
                history.add(BetaMessageParam.builder().role(BetaMessageParam.Role.USER).contentOfBetaContentBlockParams(results).build());
            }
            if (limitReached) {
                job.emit("info", "Stopped after " + maxToolCalls + " tool calls. Ask a follow-up question to continue.");
                job.finish(ChatJob.State.DONE);
                return;
            }
            if (toolCalls >= maxToolCalls) {
                // One more round so Claude can answer with what it has.
                limitReached = true;
            }
        }
    }

    private BetaMessage create(List<BetaMessageParam> history, String previousMessageId) {
        if (diagnostics) {
            try {
                return client.beta().messages().create(params(history, previousMessageId, true));
            } catch (BadRequestException e) {
                if (String.valueOf(e.getMessage()).toLowerCase().contains("diagnos")) {
                    // Cache diagnostics is a beta; if this account rejects it, carry on without it.
                    diagnostics = false;
                    LOG.warn("Claude Assistant: cache diagnostics not available, continuing without it: " + e.getMessage());
                } else {
                    throw e;
                }
            }
        }
        return client.beta().messages().create(params(history, previousMessageId, false));
    }

    private MessageCreateParams params(List<BetaMessageParam> history, String previousMessageId, boolean withDiagnostics) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                // Explicit breakpoint after tools + system: the fixed prefix always has a read point...
                .systemOfBetaTextBlockParams(Collections.singletonList(BetaTextBlockParam.builder().text(systemPrompt).cacheControl(BetaCacheControlEphemeral.builder().build()).build()))
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
                // ...and automatic caching moves a second breakpoint along the growing conversation.
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .messages(history);
        for (BetaTool tool : tools) {
            b.addTool(tool);
        }
        if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")) {
            // Server-side fallback: if a safety classifier declines, the API retries on a suitable model.
            b.addBeta("server-side-fallback-2026-07-01").putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        if (withDiagnostics) {
            // The API compares this request with the previous one and reports why the cache missed.
            b.addBeta("cache-diagnosis-2026-04-07").diagnostics(BetaDiagnosticsParam.builder().previousMessageId(Optional.ofNullable(previousMessageId)).build());
        }
        return b.build();
    }

    /** One line per API call in the server log, so cache behaviour and cost can be checked. */
    private void logUsage(State state, int call, BetaMessage response) {
        BetaUsage usage = response.usage();
        long read = usage.cacheReadInputTokens().orElse(0L);
        long written = usage.cacheCreationInputTokens().orElse(0L);
        StringBuilder sb = new StringBuilder("Claude Assistant usage: conversation ").append(state.id)
                .append(" call ").append(call)
                .append(", model ").append(response.model().asString())
                .append(", input ").append(usage.inputTokens())
                .append(", cache read ").append(read)
                .append(", cache write ").append(written)
                .append(", output ").append(usage.outputTokens())
                .append(", stop ").append(response.stopReason().map(Object::toString).orElse("?"));
        response.diagnostics().flatMap(d -> d.cacheMissReason()).ifPresent(reason -> sb.append(", cache miss: ").append(reason));
        LOG.info(sb.toString());
    }

    /** Engine-side state of a conversation, kept on the host-side Conversation as an opaque Object. */
    private static final class State {
        final String id = Long.toHexString(System.nanoTime() & 0xffffffL);
        final List<BetaMessageParam> messages = new ArrayList<>();
        volatile String lastMessageId;
    }

    private static State state(Conversation conversation) {
        synchronized (conversation) {
            if (!(conversation.history() instanceof State)) {
                conversation.setHistory(new State());
            }
            return (State) conversation.history();
        }
    }

    private static List<BetaMessageParam> snapshot(List<BetaMessageParam> history) {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    private static String describe(Throwable t) {
        if (t instanceof UnauthorizedException) {
            return "The Anthropic API key is invalid or revoked. Change it under Settings > Claude Assistant.";
        }
        if (t instanceof PermissionDeniedException) {
            return "The API key has no access to this model or feature: " + t.getMessage();
        }
        if (t instanceof RateLimitException) {
            return "Too many requests or tokens at Anthropic (rate limit). Try again in a minute.";
        }
        if (t instanceof AnthropicServiceException) {
            return "Anthropic API error (HTTP " + ((AnthropicServiceException) t).statusCode() + "): " + t.getMessage();
        }
        if (t instanceof AnthropicIoException) {
            return unreachable(t);
        }
        return "Unexpected error: " + t;
    }

    /** "Request failed" says little; the root cause says whether it was DNS, a refusal or a timeout. */
    static String unreachable(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String cause = root == t ? String.valueOf(t.getMessage()) : root.getClass().getSimpleName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
        return "Cannot reach the Anthropic API from the OIE server (" + cause + "). Check the server's internet access, firewall or proxy.";
    }
}
