package com.matthy.oie.claude.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
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
        AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(Duration.ofMinutes(10)).maxRetries(3);
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
                org.apache.logging.log4j.LogManager.getLogger(SdkModelLoop.class).warn("Claude Assistant job failed", t);
                job.emit("error", describe(t));
                job.finish(ChatJob.State.ERROR);
            }
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private void loop(ChatJob job, String userText) throws Exception {
        List<BetaMessageParam> history = history(job.conversation());
        synchronized (history) {
            history.add(BetaMessageParam.builder().role(BetaMessageParam.Role.USER).content(userText).build());
        }
        int toolCalls = 0;
        boolean limitReached = false;
        while (!job.isCancelled()) {
            BetaMessage response = client.beta().messages().create(params(snapshot(history)));
            if (job.isCancelled()) {
                return;
            }
            BetaStopReason stop = response.stopReason().orElse(null);
            if (BetaStopReason.REFUSAL.equals(stop)) {
                String why = response.stopDetails().flatMap(d -> d.explanation()).orElse("");
                job.emit("error", "Claude heeft deze vraag geweigerd." + (why.isEmpty() ? "" : " " + why));
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
                    job.emit("info", "Het antwoord is afgekapt omdat het te lang werd. Vraag eventueel om een vervolg.");
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
                    result = "Niet uitgevoerd: de limiet van " + maxToolCalls + " tool-aanroepen per vraag is bereikt. Geef nu een antwoord met de informatie die je hebt.";
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
                job.emit("info", "Gestopt na " + maxToolCalls + " tool-aanroepen. Stel een vervolgvraag om verder te gaan.");
                job.finish(ChatJob.State.DONE);
                return;
            }
            if (toolCalls >= maxToolCalls) {
                // One more round so Claude can answer with what it has.
                limitReached = true;
            }
        }
    }

    private MessageCreateParams params(List<BetaMessageParam> history) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(model)
                .maxTokens(MAX_TOKENS)
                .systemOfBetaTextBlockParams(Collections.singletonList(BetaTextBlockParam.builder().text(systemPrompt).build()))
                .thinking(BetaThinkingConfigAdaptive.builder().build())
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.of(effort)).build())
                // Caches tools + system + history up to the last message, so follow-up turns are cheap.
                .cacheControl(BetaCacheControlEphemeral.builder().build())
                .messages(history);
        for (BetaTool tool : tools) {
            b.addTool(tool);
        }
        if (model.startsWith("claude-opus-5") || model.startsWith("claude-fable-5")) {
            // Server-side fallback: if a safety classifier declines, the API retries on a suitable model.
            b.addBeta("server-side-fallback-2026-07-01").putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
        }
        return b.build();
    }

    /** The conversation history lives on the host-side Conversation, typed only as Object there. */
    @SuppressWarnings("unchecked")
    private static List<BetaMessageParam> history(Conversation conversation) {
        synchronized (conversation) {
            if (conversation.history() == null) {
                conversation.setHistory(new ArrayList<BetaMessageParam>());
            }
            return (List<BetaMessageParam>) conversation.history();
        }
    }

    private static List<BetaMessageParam> snapshot(List<BetaMessageParam> history) {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    private static String describe(Throwable t) {
        if (t instanceof UnauthorizedException) {
            return "De Anthropic API-key is ongeldig of ingetrokken. Pas hem aan via Settings > Claude Assistant.";
        }
        if (t instanceof PermissionDeniedException) {
            return "De API-key heeft geen toegang tot dit model of deze functie: " + t.getMessage();
        }
        if (t instanceof RateLimitException) {
            return "Te veel aanvragen of tokens bij Anthropic (rate limit). Probeer het over een minuut opnieuw.";
        }
        if (t instanceof AnthropicServiceException) {
            return "Fout van de Anthropic API (HTTP " + ((AnthropicServiceException) t).statusCode() + "): " + t.getMessage();
        }
        if (t instanceof AnthropicIoException) {
            return "Kan de Anthropic API niet bereiken vanaf de OIE-server: " + t.getMessage() + ". Controleer internettoegang/proxy van de server.";
        }
        return "Onverwachte fout: " + t;
    }
}
