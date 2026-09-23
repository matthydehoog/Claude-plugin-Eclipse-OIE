package com.matthy.oie.claude.server;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    static final String SYSTEM_PROMPT = String.join("\n",
            "Je bent de Claude-assistent binnen de Administrator van Open Integration Engine (OIE, een fork van Mirth Connect).",
            "Je helpt integratiespecialisten met hun OIE-server: channels, berichten, fouten, scripts, code templates en serverstatus.",
            "Antwoord in de taal van de gebruiker (meestal Nederlands), beknopt en concreet.",
            "",
            "Werkwijze:",
            "- Gebruik de oie_-tools om feiten op te halen in plaats van te gokken. Begin bij een algemene vraag met oie_list_channels.",
            "- Bij fouten: zoek de berichten met status ERROR, groepeer ze op foutmelding, lees de channelconfiguratie en wijs het filter, de transformerstap of de connector aan die faalt. Geef een concrete oplossing, met code als het een script betreft.",
            "- Channel-JavaScript draait in Rhino (ES5 met enkele ES6-uitbreidingen) met de Mirth-API: msg, tmp, channelMap, globalMap, logger, router, enzovoort.",
            "",
            "Acties (deployen, starten/stoppen, statistieken resetten, bericht opnieuw verwerken, bericht versturen, script aanpassen):",
            "- Elke actie-tool legt je voorstel eerst ter bevestiging voor aan de gebruiker. Leg in één zin uit waarom je de actie voorstelt voordat je de tool aanroept.",
            "- Stel een actie alleen voor als die nodig is voor de vraag van de gebruiker. Een geweigerde actie probeer je niet opnieuw.",
            "- Wees extra voorzichtig met productie-channels (namen met PROD of van ziekenhuizen); stel liever eerst een test voor.",
            "",
            "Privacy: berichtinhoud, logs en foutmeldingen worden vóór verzending gemaskeerd; patiëntvelden (HL7 PID, GDT 3000-3107, BSN) zie je als ***.",
            "Probeer gemaskeerde gegevens niet te achterhalen en vraag de gebruiker niet om patiëntgegevens.",
            "",
            "Opmaak: de chat toont Markdown (koppen, vet, cursief, lijsten, tabellen en codeblokken). Houd tabellen smal.");

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
                m.put("description", spec.description + (spec.action ? " (ACTIE: de gebruiker moet dit eerst bevestigen.)" : ""));
                m.put("properties", spec.properties);
                m.put("required", spec.required);
                specs.add(m);
            }
            try {
                newLoop = engine.create(settings, SYSTEM_PROMPT, specs, this::callTool);
            } catch (Exception e) {
                logger.error("Claude Assistant: could not create the model client", e);
                engineError = "Kan de Claude-client niet starten: " + e;
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
                throw new IllegalStateException("De Claude-assistent kan niet starten: " + engineError);
            }
            throw new IllegalStateException("Er is nog geen Anthropic API-key ingesteld. Vul die in via Settings > Claude Assistant.");
        }
        Conversation conversation;
        if (conversationId == null || conversationId.isBlank()) {
            conversation = new Conversation(userId);
            conversations.put(conversation.id, conversation);
        } else {
            conversation = conversations.get(conversationId);
            if (conversation == null || conversation.userId != userId) {
                throw new IllegalArgumentException("Gesprek niet gevonden of verlopen. Begin een nieuw gesprek.");
            }
        }
        ChatJob job;
        synchronized (conversation) {
            if (conversation.activeJob != null && !isFinished(conversation.activeJob)) {
                throw new IllegalStateException("Claude is nog bezig met je vorige vraag.");
            }
            job = new ChatJob(conversation);
            conversation.activeJob = job;
            conversation.lastUsed = System.currentTimeMillis();
        }
        jobs.put(job.id, job);
        String userText = buildUserText(message, context);
        job.setFuture(executor.submit(() -> {
            try {
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
            throw new IllegalArgumentException("Job niet gevonden of verlopen.");
        }
        return job;
    }

    /** Runs (or rejects) the pending action on the servlet thread, as the approving user. */
    public String decide(ChatJob job, String actionId, boolean approved, ServerEventContext context) {
        ChatJob.PendingAction pending = job.pending();
        if (pending == null || !pending.id.equals(actionId)) {
            throw new IllegalStateException("Deze actie wacht niet (meer) op een beslissing.");
        }
        String result;
        if (!approved) {
            result = "De gebruiker heeft deze actie geweigerd. Voer haar niet uit en stel haar niet opnieuw voor, tenzij de gebruiker erom vraagt.";
            job.emit("info", "Actie geweigerd: " + pending.prepared.title);
        } else {
            try {
                result = pending.prepared.runner.run(context);
                job.emit("action", pending.prepared.title + ": " + result);
                audit(context, pending, ServerEvent.Outcome.SUCCESS, result);
            } catch (Exception e) {
                logger.warn("Claude Assistant action " + pending.toolName + " failed", e);
                result = "Mislukt: " + e.getMessage();
                job.emit("error", pending.prepared.title + " mislukt: " + e.getMessage());
                audit(context, pending, ServerEvent.Outcome.FAILURE, String.valueOf(e.getMessage()));
            }
        }
        job.resumed();
        pending.decision.complete(result);
        return result;
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
            return t.run(name, input);
        }
        OieTools.PreparedAction prepared = t.prepare(name, input);
        // The confirmation dialog shows the detail, so it goes through the masker like everything else.
        OieTools.PreparedAction shown = new OieTools.PreparedAction(prepared.title, masker.mask(prepared.detail), prepared.runner);
        ChatJob.PendingAction pending = job.await(name, shown);
        try {
            return pending.decision.get(ACTION_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            job.resumed();
            job.emit("info", "Geen beslissing binnen " + ACTION_TIMEOUT_MINUTES + " minuten; de actie is niet uitgevoerd.");
            return "De gebruiker heeft niet op tijd beslist; de actie is niet uitgevoerd.";
        }
    }

    // ------------------------------------------------------------------ helpers

    private String buildUserText(String message, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("[Servertijd: ").append(ZonedDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z"))).append("]\n");
        if (context != null && !context.isBlank()) {
            sb.append("[Context uit de Administrator: ").append(context.trim()).append("]\n");
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
