package com.matthy.oie.claude.server;

import java.io.File;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.donkey.model.channel.DebugOptions;
import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.model.FilterTransformerElement;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.codetemplates.CodeTemplate;
import com.mirth.connect.model.codetemplates.CodeTemplateLibrary;
import com.mirth.connect.model.converters.ObjectXMLSerializer;
import com.mirth.connect.model.filters.EventFilter;
import com.mirth.connect.model.filters.MessageFilter;
import com.mirth.connect.plugins.ServicePlugin;
import com.mirth.connect.server.channel.ErrorTaskHandler;
import com.mirth.connect.server.controllers.ChannelController;
import com.mirth.connect.server.controllers.CodeTemplateController;
import com.mirth.connect.server.controllers.ConfigurationController;
import com.mirth.connect.server.controllers.ControllerFactory;
import com.mirth.connect.server.controllers.EngineController;
import com.mirth.connect.server.controllers.EventController;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.server.controllers.MessageController;

/**
 * The tools Claude can call. Read tools run straight away inside the chat job. Action tools only
 * describe what they would do; they run after the user approves them in the Administrator.
 */
public class OieTools {

    /** Longest tool result sent back to Claude, in characters. */
    static final int MAX_RESULT_CHARS = 60_000;

    private static final String[] MESSAGE_STATUSES = { "RECEIVED", "FILTERED", "TRANSFORMED", "SENT", "QUEUED", "ERROR", "PENDING" };
    private static final String[] SCRIPT_TYPES = { "deploy", "undeploy", "preprocessor", "postprocessor", "filter_rule", "transformer_step", "response_transformer_step" };

    /** A tool as offered to Claude. */
    public static final class Spec {
        final String name;
        final String description;
        final Map<String, Object> properties;
        final List<String> required;
        final boolean action;

        Spec(String name, boolean action, String description, Map<String, Object> properties, String... required) {
            this.name = name;
            this.action = action;
            this.description = description;
            this.properties = properties;
            this.required = Arrays.asList(required);
        }
    }

    /** An action Claude proposed, waiting for the user's decision. */
    public static final class PreparedAction {
        final String title;
        final String detail;
        final ActionRunner runner;

        PreparedAction(String title, String detail, ActionRunner runner) {
            this.title = title;
            this.detail = detail;
            this.runner = runner;
        }
    }

    public interface ActionRunner {
        /** Runs the action as the user who approved it and returns the result for Claude. */
        String run(ServerEventContext context) throws Exception;
    }

    private interface ReadTool {
        String run(JsonNode input) throws Exception;
    }

    private interface ActionTool {
        PreparedAction prepare(JsonNode input) throws Exception;
    }

    private final Masker masker;
    private final List<Spec> specs = new ArrayList<>();
    private final Map<String, ReadTool> readTools = new HashMap<>();
    private final Map<String, ActionTool> actionTools = new HashMap<>();

    private final ChannelController channelController;
    private final EngineController engineController;
    private final MessageController messageController;
    private final EventController eventController;
    private final CodeTemplateController codeTemplateController;
    private final ConfigurationController configurationController;
    private final ExtensionController extensionController;

    public OieTools(Masker masker) {
        this.masker = masker;
        ControllerFactory f = ControllerFactory.getFactory();
        channelController = f.createChannelController();
        engineController = f.createEngineController();
        messageController = f.createMessageController();
        eventController = f.createEventController();
        codeTemplateController = f.createCodeTemplateController();
        configurationController = f.createConfigurationController();
        extensionController = f.createExtensionController();
        registerReadTools();
        registerActionTools();
    }

    public List<Spec> specs() {
        return specs;
    }

    public boolean isAction(String name) {
        return actionTools.containsKey(name);
    }

    /** Runs a read tool. The result is masked and clipped, ready to send to Claude. */
    public String run(String name, JsonNode input) throws Exception {
        ReadTool tool = readTools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Onbekende tool: " + name);
        }
        return clip(masker.mask(tool.run(input)));
    }

    public PreparedAction prepare(String name, JsonNode input) throws Exception {
        ActionTool tool = actionTools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Onbekende actie: " + name);
        }
        return tool.prepare(input);
    }

    String clip(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= MAX_RESULT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_RESULT_CHARS) + "\n\n[... afgekapt: " + (text.length() - MAX_RESULT_CHARS) + " tekens weggelaten. Verfijn de vraag.]";
    }

    // ------------------------------------------------------------------ read tools

    private void registerReadTools() {
        read("oie_server_info", "Versie, status, JVM/OS-info, geheugen en schijfruimte van de OIE-server.", props(), in -> serverInfo());

        read("oie_list_channels", "Tabel van alle channels met deploy-status (STARTED/STOPPED/PAUSED/UNDEPLOYED) en tellers (received/filtered/sent/queued/error), channels met fouten bovenaan. Begin hier.", props(
                "includeUndeployed", bool("Ook niet-gedeployde channels tonen (standaard true)"),
                "includeConnectors", bool("Ook tellers per source/destination tonen (standaard false)")), in -> listChannels(in.path("includeUndeployed").asBoolean(true), in.path("includeConnectors").asBoolean(false)));

        read("oie_get_channel", "Volledige channelconfiguratie als OIE-export-XML: connectors, filters, transformers (JavaScript), data type properties en deploy/undeploy/pre/postprocessor-scripts.", props(
                "channel", str("Channel ID (UUID) of channelnaam")), in -> ObjectXMLSerializer.getInstance().serialize(channel(in)), "channel");

        read("oie_channel_scripts", "Overzicht van alle scripts in een channel met hun plaats (connector-metaDataId en index), zodat je weet wat oie_update_channel_script moet aanpassen.", props(
                "channel", str("Channel ID (UUID) of channelnaam")), in -> channelScripts(channel(in)), "channel");

        read("oie_channel_statistics", "Tellers per connector (source = metaDataId 0, destinations 1..n) voor één channel.", props(
                "channel", str("Channel ID (UUID) of channelnaam")), in -> channelStatistics(channel(in)), "channel");

        read("oie_search_messages", "Zoekt berichten in een channel op status, periode of tekst. Geeft per bericht ID, datum, status per connector en foutmelding. Met includeContent ook de inhoud (gemaskeerd).", props(
                "channel", str("Channel ID (UUID) of channelnaam"),
                "statuses", arr(enumStr("Berichtstatus", MESSAGE_STATUSES), "Bijv. [\"ERROR\"] of [\"QUEUED\"]"),
                "startDate", str("ISO-datum/tijd, bijv. 2026-09-23T00:00:00+02:00"),
                "endDate", str("ISO-datum/tijd"),
                "textSearch", str("Zoekt in de inhoud (kan traag zijn)"),
                "onlyWithErrors", bool("Alleen berichten met een fout"),
                "includeContent", bool("Ook de inhoud meesturen (standaard false)"),
                "limit", integer("Maximaal aantal berichten, 1-50 (standaard 20)"),
                "offset", integer("Aantal over te slaan berichten (standaard 0)")), this::searchMessages, "channel");

        read("oie_get_message", "Eén bericht met alle connectorberichten: raw, transformed, encoded, sent, response, maps en foutmeldingen (patiëntgegevens gemaskeerd).", props(
                "channel", str("Channel ID (UUID) of channelnaam"),
                "messageId", integer("Bericht-ID"),
                "metaDataIds", arr(integer("Connector"), "Beperk tot deze connectors (0 = source)")), this::getMessage, "channel", "messageId");

        read("oie_events", "Audit-/server-events (deploys, fouten, logins), nieuwste eerst. Filter op level, naam en periode.", props(
                "level", enumStr("Level", "INFORMATION", "WARNING", "ERROR"),
                "name", str("Filter op (deel van) de eventnaam"),
                "startDate", str("ISO-datum/tijd"),
                "endDate", str("ISO-datum/tijd"),
                "limit", integer("Maximaal aantal events, 1-200 (standaard 50)")), this::events);

        read("oie_server_log", "Meest recente regels uit het OIE-serverlog. Handig bij deploy- en scriptfouten.", props(
                "fetchSize", integer("Aantal regels, 1-500 (standaard 100)")), in -> serverLog(in.path("fetchSize").asInt(100)));

        read("oie_list_code_templates", "Code template libraries met hun templates (gedeelde functies) en aan welke channels ze gekoppeld zijn.", props(
                "includeCode", bool("Ook de code meesturen (standaard false)")), in -> codeTemplates(in.path("includeCode").asBoolean(false)));

        read("oie_get_code_template", "Eén code template inclusief code.", props(
                "codeTemplateId", str("ID van de code template")), in -> ObjectXMLSerializer.getInstance().serialize(codeTemplateController.getCodeTemplateById(text(in, "codeTemplateId"))), "codeTemplateId");

        read("oie_configuration_map", "Sleutels uit de Configuration Map. Waarden worden nooit teruggegeven omdat ze vaak wachtwoorden of paden bevatten.", props(), in -> String.join("\n", new TreeMap<>(configurationController.getConfigurationMap()).keySet()));
    }

    private String serverInfo() {
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();
        sb.append("Versie: ").append(configurationController.getServerVersion()).append(" (build ").append(configurationController.getBuildDate()).append(")\n");
        sb.append("Server-ID: ").append(configurationController.getServerId()).append('\n');
        sb.append("Status: ").append(configurationController.getStatus()).append(" (0 = draait)\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
        sb.append("CPU's: ").append(rt.availableProcessors()).append('\n');
        long used = rt.totalMemory() - rt.freeMemory();
        sb.append(String.format(Locale.ROOT, "Heap: %d MB in gebruik, %d MB gealloceerd, %d MB max%n", used >> 20, rt.totalMemory() >> 20, rt.maxMemory() >> 20));
        File appDir = new File(System.getProperty("user.dir"));
        sb.append(String.format(Locale.ROOT, "Schijf (%s): %d GB vrij van %d GB%n", appDir.getAbsolutePath(), appDir.getUsableSpace() >> 30, appDir.getTotalSpace() >> 30));
        sb.append("Gedeployde channels: ").append(engineController.getDeployedIds().size()).append(" van ").append(channelController.getChannelIds().size()).append('\n');
        return sb.toString();
    }

    private String listChannels(boolean includeUndeployed, boolean includeConnectors) {
        List<DashboardStatus> statuses = new ArrayList<>(engineController.getChannelStatusList(null, includeUndeployed));
        if (statuses.isEmpty()) {
            return "Geen channels gevonden.";
        }
        statuses.sort((a, b) -> {
            int byErrors = Long.compare(count(b, Status.ERROR), count(a, Status.ERROR));
            return byErrors != 0 ? byErrors : a.getName().compareToIgnoreCase(b.getName());
        });
        StringBuilder sb = new StringBuilder("| Channel | Status | Received | Filtered | Sent | Queued | Errors | ID |\n|---|---|---:|---:|---:|---:|---:|---|\n");
        long totalErrors = 0;
        int withErrors = 0;
        for (DashboardStatus s : statuses) {
            row(sb, s.getName(), s, s.getChannelId());
            if (includeConnectors && s.getChildStatuses() != null) {
                for (DashboardStatus c : s.getChildStatuses()) {
                    row(sb, "↳ " + c.getName(), c, "");
                }
            }
            long errors = count(s, Status.ERROR);
            totalErrors += errors;
            if (errors > 0) {
                withErrors++;
            }
        }
        long started = statuses.stream().filter(s -> s.getState() == DeployedState.STARTED).count();
        sb.append('\n').append(statuses.size()).append(" channels, ").append(started).append(" gestart, ").append(totalErrors).append(" fouten in ").append(withErrors).append(" channel(s).\n");
        sb.append("Tellers zijn sinds de laatste reset van de statistieken. 'Sent' telt per destination.");
        return sb.toString();
    }

    private static void row(StringBuilder sb, String name, DashboardStatus s, String id) {
        String state = s.getState() == null ? "UNDEPLOYED" : s.getState().name();
        sb.append("| ").append(name).append(" | ").append(state).append(" | ").append(count(s, Status.RECEIVED)).append(" | ").append(count(s, Status.FILTERED)).append(" | ").append(count(s, Status.SENT)).append(" | ").append(s.getQueued() == null ? 0 : s.getQueued()).append(" | ").append(count(s, Status.ERROR)).append(" | ").append(id).append(" |\n");
    }

    private static long count(DashboardStatus s, Status status) {
        Map<Status, Long> stats = s.getStatistics();
        Long n = stats == null ? null : stats.get(status);
        return n == null ? 0 : n;
    }

    private String channelStatistics(Channel channel) {
        DashboardStatus status = engineController.getChannelStatus(channel.getId());
        if (status == null) {
            return "Channel '" + channel.getName() + "' is niet gedeployd; er zijn geen actuele tellers.";
        }
        StringBuilder sb = new StringBuilder("Channel '" + channel.getName() + "' (" + status.getState() + ")\n\n| Connector | metaDataId | Status | Received | Filtered | Sent | Queued | Errors |\n|---|---:|---|---:|---:|---:|---:|---:|\n");
        List<DashboardStatus> rows = new ArrayList<>();
        rows.add(status);
        if (status.getChildStatuses() != null) {
            rows.addAll(status.getChildStatuses());
        }
        for (DashboardStatus s : rows) {
            sb.append("| ").append(s == status ? "(channel totaal)" : s.getName()).append(" | ").append(s.getMetaDataId() == null ? "" : s.getMetaDataId()).append(" | ").append(s.getState()).append(" | ").append(count(s, Status.RECEIVED)).append(" | ").append(count(s, Status.FILTERED)).append(" | ").append(count(s, Status.SENT)).append(" | ").append(s.getQueued() == null ? 0 : s.getQueued()).append(" | ").append(count(s, Status.ERROR)).append(" |\n");
        }
        return sb.toString();
    }

    private String channelScripts(Channel channel) throws Exception {
        StringBuilder sb = new StringBuilder("Scripts in channel '" + channel.getName() + "' (revisie " + channel.getRevision() + ")\n\n");
        sb.append("- deploy: ").append(lines(channel.getDeployScript())).append('\n');
        sb.append("- undeploy: ").append(lines(channel.getUndeployScript())).append('\n');
        sb.append("- preprocessor: ").append(lines(channel.getPreprocessingScript())).append('\n');
        sb.append("- postprocessor: ").append(lines(channel.getPostprocessingScript())).append('\n');
        for (Connector c : connectors(channel)) {
            sb.append("\nConnector ").append(c.getMetaDataId()).append(" '").append(c.getName()).append("':\n");
            listElements(sb, "filter_rule", c.getFilter() == null ? null : c.getFilter().getElements());
            listElements(sb, "transformer_step", c.getTransformer() == null ? null : c.getTransformer().getElements());
            if (c.getMetaDataId() > 0) {
                listElements(sb, "response_transformer_step", c.getResponseTransformer() == null ? null : c.getResponseTransformer().getElements());
            }
        }
        sb.append("\nAlleen elementen van type JavaScript kunnen met oie_update_channel_script worden aangepast.");
        return sb.toString();
    }

    private static void listElements(StringBuilder sb, String type, List<? extends FilterTransformerElement> elements) {
        if (elements == null || elements.isEmpty()) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            FilterTransformerElement e = elements.get(i);
            sb.append("  - ").append(type).append(" index ").append(i).append(": '").append(e.getName()).append("' (").append(e.getType()).append(")\n");
        }
    }

    private static String lines(String script) {
        if (script == null || script.isBlank()) {
            return "(leeg)";
        }
        return script.split("\n", -1).length + " regels";
    }

    private String searchMessages(JsonNode in) throws Exception {
        Channel channel = channel(in);
        MessageFilter filter = new MessageFilter();
        if (in.path("statuses").isArray() && in.path("statuses").size() > 0) {
            Set<Status> statuses = new HashSet<>();
            for (JsonNode s : in.path("statuses")) {
                statuses.add(Status.valueOf(s.asText().toUpperCase(Locale.ROOT)));
            }
            filter.setStatuses(statuses);
        }
        filter.setStartDate(date(in, "startDate"));
        filter.setEndDate(date(in, "endDate"));
        if (!in.path("textSearch").asText("").isBlank()) {
            filter.setTextSearch(in.path("textSearch").asText());
        }
        if (in.path("onlyWithErrors").asBoolean(false)) {
            filter.setError(true);
        }
        boolean includeContent = in.path("includeContent").asBoolean(false);
        int limit = Math.max(1, Math.min(50, in.path("limit").asInt(20)));
        int offset = Math.max(0, in.path("offset").asInt(0));

        long total = messageController.getMessageCount(filter, channel.getId());
        List<Message> messages = messageController.getMessages(filter, channel.getId(), includeContent, offset, limit);
        if (includeContent) {
            return total + " bericht(en) gevonden, " + messages.size() + " getoond (offset " + offset + ").\n\n" + ObjectXMLSerializer.getInstance().serialize(messages);
        }
        StringBuilder sb = new StringBuilder(total + " bericht(en) gevonden, " + messages.size() + " getoond (offset " + offset + ").\n\n");
        for (Message m : messages) {
            sb.append("Bericht ").append(m.getMessageId()).append(" — ontvangen ").append(format(m.getReceivedDate())).append('\n');
            for (Map.Entry<Integer, ConnectorMessage> e : new TreeMap<>(m.getConnectorMessages()).entrySet()) {
                ConnectorMessage cm = e.getValue();
                sb.append("  [").append(e.getKey()).append("] ").append(cm.getConnectorName()).append(": ").append(cm.getStatus());
                String error = cm.getProcessingError() != null ? cm.getProcessingError() : cm.getResponseError();
                if (error != null && !error.isBlank()) {
                    sb.append(" — fout: ").append(firstLines(error, 6));
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private String getMessage(JsonNode in) throws Exception {
        Channel channel = channel(in);
        long messageId = in.path("messageId").asLong();
        List<Integer> metaDataIds = null;
        if (in.path("metaDataIds").isArray() && in.path("metaDataIds").size() > 0) {
            metaDataIds = new ArrayList<>();
            for (JsonNode n : in.path("metaDataIds")) {
                metaDataIds.add(n.asInt());
            }
        }
        Message message = messageController.getMessageContent(channel.getId(), messageId, metaDataIds);
        if (message == null) {
            return "Bericht " + messageId + " niet gevonden in channel '" + channel.getName() + "'.";
        }
        return ObjectXMLSerializer.getInstance().serialize(message);
    }

    private String events(JsonNode in) throws Exception {
        EventFilter filter = new EventFilter();
        String level = in.path("level").asText("");
        if (!level.isBlank()) {
            filter.setLevels(Collections.singleton(ServerEvent.Level.valueOf(level.toUpperCase(Locale.ROOT))));
        }
        if (!in.path("name").asText("").isBlank()) {
            filter.setName(in.path("name").asText());
        }
        filter.setStartDate(date(in, "startDate"));
        filter.setEndDate(date(in, "endDate"));
        int limit = Math.max(1, Math.min(200, in.path("limit").asInt(50)));
        List<ServerEvent> events = eventController.getEvents(filter, 0, limit);
        if (events.isEmpty()) {
            return "Geen events gevonden.";
        }
        StringBuilder sb = new StringBuilder();
        for (ServerEvent e : events) {
            sb.append(format(e.getEventTime())).append("  ").append(e.getLevel()).append("  ").append(e.getName()).append("  (").append(e.getOutcome()).append(", gebruiker ").append(e.getUserId()).append(")\n");
            if (e.getAttributes() != null) {
                for (Map.Entry<String, String> a : e.getAttributes().entrySet()) {
                    sb.append("    ").append(a.getKey()).append(": ").append(firstLines(a.getValue(), 15)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private String serverLog(int fetchSize) throws Exception {
        ServicePlugin provider = extensionController.getServicePlugins().get("Server Log");
        if (provider == null) {
            return "De Server Log-extensie is niet geïnstalleerd of niet gestart.";
        }
        // The serverlog extension lives in its own jar, so call it reflectively.
        Method getLogs = provider.getClass().getMethod("getServerLogs", int.class, Long.class);
        List<?> items = (List<?>) getLogs.invoke(provider, Math.max(1, Math.min(500, fetchSize)), null);
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            sb.append(getter(item, "getDate")).append("  ").append(getter(item, "getLevel")).append("  [").append(getter(item, "getCategory")).append("] ").append(getter(item, "getMessage")).append('\n');
            Object throwable = getter(item, "getThrowableInformation");
            if (throwable != null && !String.valueOf(throwable).isBlank()) {
                sb.append("    ").append(firstLines(String.valueOf(throwable), 12)).append('\n');
            }
        }
        return sb.length() == 0 ? "Het serverlog is leeg." : sb.toString();
    }

    private static Object getter(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private String codeTemplates(boolean includeCode) throws Exception {
        List<CodeTemplateLibrary> libraries = codeTemplateController.getLibraries(null, includeCode);
        if (includeCode) {
            return ObjectXMLSerializer.getInstance().serialize(libraries);
        }
        Map<String, String> channelNames = new HashMap<>();
        for (Channel c : channelController.getChannels(null)) {
            channelNames.put(c.getId(), c.getName());
        }
        StringBuilder sb = new StringBuilder();
        for (CodeTemplateLibrary lib : libraries) {
            sb.append("Library '").append(lib.getName()).append("' (").append(lib.getId()).append(")");
            List<String> enabled = new ArrayList<>();
            if (lib.getEnabledChannelIds() != null) {
                for (String id : lib.getEnabledChannelIds()) {
                    enabled.add(channelNames.getOrDefault(id, id));
                }
            }
            sb.append(lib.isIncludeNewChannels() ? " — ook voor nieuwe channels" : "").append("\n  gekoppeld aan: ").append(enabled.isEmpty() ? "(geen channels)" : String.join(", ", enabled)).append('\n');
            if (lib.getCodeTemplates() != null) {
                for (CodeTemplate t : lib.getCodeTemplates()) {
                    sb.append("  - ").append(t.getName()).append(" (").append(t.getId()).append(")\n");
                }
            }
        }
        return sb.length() == 0 ? "Geen code template libraries." : sb.toString();
    }

    // ------------------------------------------------------------------ action tools

    private void registerActionTools() {
        Map<String, Object> channelOnly = props("channel", str("Channel ID (UUID) of channelnaam"));

        action("oie_deploy_channel", "Deployt (of herdeployt) een channel. Vereist bevestiging door de gebruiker.", channelOnly, in -> {
            Channel c = channel(in);
            return new PreparedAction("Channel deployen", "Channel '" + c.getName() + "' (" + c.getId() + ") deployen" + (engineController.isDeployed(c.getId()) ? " (is al gedeployd: wordt opnieuw gedeployd)" : "") + ".", ctx -> {
                ErrorTaskHandler handler = new ErrorTaskHandler();
                engineController.deployChannels(Collections.singleton(c.getId()), ctx, handler, new DebugOptions());
                return handled(handler, "Channel '" + c.getName() + "' is gedeployd.");
            });
        }, "channel");

        action("oie_undeploy_channel", "Undeployt een channel. Vereist bevestiging door de gebruiker.", channelOnly, in -> {
            Channel c = channel(in);
            return new PreparedAction("Channel undeployen", "Channel '" + c.getName() + "' (" + c.getId() + ") undeployen. De channel verwerkt daarna geen berichten meer.", ctx -> {
                ErrorTaskHandler handler = new ErrorTaskHandler();
                engineController.undeployChannels(Collections.singleton(c.getId()), ctx, handler);
                return handled(handler, "Channel '" + c.getName() + "' is ge-undeployd.");
            });
        }, "channel");

        channelStateAction("oie_start_channel", "Start een gedeployde channel.", "Channel starten", "gestart", (ids, h) -> engineController.startChannels(ids, h));
        channelStateAction("oie_stop_channel", "Stopt een gedeployde channel.", "Channel stoppen", "gestopt", (ids, h) -> engineController.stopChannels(ids, h));
        channelStateAction("oie_pause_channel", "Pauzeert een gedeployde channel (de source stopt, de destinations werken hun queue af).", "Channel pauzeren", "gepauzeerd", (ids, h) -> engineController.pauseChannels(ids, h));
        channelStateAction("oie_resume_channel", "Hervat een gepauzeerde channel.", "Channel hervatten", "hervat", (ids, h) -> engineController.resumeChannels(ids, h));

        action("oie_reset_statistics", "Zet de tellers (received/filtered/sent/error) van een channel op nul. Vereist bevestiging.", channelOnly, in -> {
            Channel c = channel(in);
            return new PreparedAction("Statistieken resetten", "Tellers van channel '" + c.getName() + "' op nul zetten (received, filtered, sent en error). Berichten zelf blijven bewaard.", ctx -> {
                List<Integer> metaDataIds = new ArrayList<>();
                metaDataIds.add(null);
                for (Connector connector : connectors(c)) {
                    metaDataIds.add(connector.getMetaDataId());
                }
                Map<String, List<Integer>> map = new HashMap<>();
                map.put(c.getId(), metaDataIds);
                channelController.resetStatistics(map, new HashSet<>(Arrays.asList(Status.RECEIVED, Status.FILTERED, Status.SENT, Status.ERROR)));
                return "Statistieken van '" + c.getName() + "' zijn gereset.";
            });
        }, "channel");

        action("oie_reprocess_message", "Verwerkt een bestaand bericht opnieuw door de channel. Vereist bevestiging.", props(
                "channel", str("Channel ID (UUID) of channelnaam"),
                "messageId", integer("Bericht-ID"),
                "replace", bool("Het bestaande bericht overschrijven in plaats van een nieuw bericht te maken (standaard false)")), in -> {
            Channel c = channel(in);
            long messageId = in.path("messageId").asLong();
            boolean replace = in.path("replace").asBoolean(false);
            return new PreparedAction("Bericht opnieuw verwerken", "Bericht " + messageId + " in channel '" + c.getName() + "' opnieuw verwerken" + (replace ? ", het bestaande bericht wordt overschreven." : " als nieuw bericht."), ctx -> {
                MessageFilter filter = new MessageFilter();
                filter.setMinMessageId(messageId);
                filter.setMaxMessageId(messageId);
                messageController.reprocessMessages(c.getId(), filter, replace, null);
                return "Bericht " + messageId + " is opnieuw verwerkt. Zoek met oie_search_messages naar het resultaat.";
            });
        }, "channel", "messageId");

        action("oie_send_message", "Stuurt een raw bericht naar de source van een gedeployde channel, bijvoorbeeld om een fix te testen. Vereist bevestiging.", props(
                "channel", str("Channel ID (UUID) of channelnaam"),
                "message", str("Raw bericht in het inbound data type van de channel (GDT, HL7 ER7, XML, ...)")), in -> {
            Channel c = channel(in);
            String raw = text(in, "message");
            return new PreparedAction("Bericht versturen", "Dit bericht naar channel '" + c.getName() + "' sturen:\n\n" + raw, ctx -> {
                DispatchResult result = engineController.dispatchRawMessage(c.getId(), new RawMessage(raw), false, true);
                return result == null ? "Het bericht is niet verwerkt; is de channel gestart?" : "Bericht verwerkt als bericht-ID " + result.getMessageId() + ".";
            });
        }, "channel", "message");

        action("oie_update_channel_script", "Vervangt één JavaScript-script in een channel: het deploy-, undeploy-, preprocessor- of postprocessorscript, of een JavaScript-filterregel of -transformerstap (zie oie_channel_scripts voor metaDataId en index). Slaat de channel op maar deployt niet. Vereist bevestiging.", props(
                "channel", str("Channel ID (UUID) of channelnaam"),
                "scriptType", enumStr("Soort script", SCRIPT_TYPES),
                "metaDataId", integer("Connector (0 = source), alleen voor filter_rule/transformer_step/response_transformer_step"),
                "index", integer("Positie van de regel/stap (vanaf 0), alleen voor filter_rule/transformer_step/response_transformer_step"),
                "script", str("Het volledige nieuwe script")), this::prepareScriptUpdate, "channel", "scriptType", "script");
    }

    private interface ChannelTask {
        void run(Set<String> channelIds, ErrorTaskHandler handler);
    }

    private void channelStateAction(String name, String description, String title, String done, ChannelTask task) {
        action(name, description + " Vereist bevestiging door de gebruiker.", props("channel", str("Channel ID (UUID) of channelnaam")), in -> {
            Channel c = channel(in);
            if (!engineController.isDeployed(c.getId())) {
                throw new IllegalStateException("Channel '" + c.getName() + "' is niet gedeployd.");
            }
            return new PreparedAction(title, title + ": '" + c.getName() + "' (" + c.getId() + ").", ctx -> {
                ErrorTaskHandler handler = new ErrorTaskHandler();
                task.run(Collections.singleton(c.getId()), handler);
                return handled(handler, "Channel '" + c.getName() + "' is " + done + ".");
            });
        }, "channel");
    }

    private static String handled(ErrorTaskHandler handler, String success) {
        if (handler.isErrored()) {
            Exception e = handler.getError();
            return "Mislukt: " + (e == null ? "onbekende fout" : e.getMessage());
        }
        return success;
    }

    private PreparedAction prepareScriptUpdate(JsonNode in) throws Exception {
        Channel current = channel(in);
        String type = text(in, "scriptType");
        String script = text(in, "script");
        int metaDataId = in.path("metaDataId").asInt(-1);
        int index = in.path("index").asInt(-1);

        // Work on a copy: the controller hands out its cached instance.
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        Channel copy = serializer.deserialize(serializer.serialize(current), Channel.class);

        String location;
        String oldScript;
        Function<Channel, Void> apply;
        switch (type) {
            case "deploy":
                location = "deployscript";
                oldScript = copy.getDeployScript();
                apply = ch -> { ch.setDeployScript(script); return null; };
                break;
            case "undeploy":
                location = "undeployscript";
                oldScript = copy.getUndeployScript();
                apply = ch -> { ch.setUndeployScript(script); return null; };
                break;
            case "preprocessor":
                location = "preprocessor";
                oldScript = copy.getPreprocessingScript();
                apply = ch -> { ch.setPreprocessingScript(script); return null; };
                break;
            case "postprocessor":
                location = "postprocessor";
                oldScript = copy.getPostprocessingScript();
                apply = ch -> { ch.setPostprocessingScript(script); return null; };
                break;
            case "filter_rule":
            case "transformer_step":
            case "response_transformer_step": {
                Connector connector = connectors(copy).stream().filter(c -> c.getMetaDataId() == metaDataId).findFirst().orElseThrow(() -> new IllegalArgumentException("Connector met metaDataId " + metaDataId + " bestaat niet in '" + current.getName() + "'."));
                List<? extends FilterTransformerElement> elements;
                if (type.equals("filter_rule")) {
                    elements = connector.getFilter().getElements();
                } else if (type.equals("transformer_step")) {
                    elements = connector.getTransformer().getElements();
                } else {
                    elements = connector.getResponseTransformer() == null ? Collections.emptyList() : connector.getResponseTransformer().getElements();
                }
                if (index < 0 || index >= elements.size()) {
                    throw new IllegalArgumentException("Index " + index + " bestaat niet; connector '" + connector.getName() + "' heeft " + elements.size() + " " + type + "-element(en).");
                }
                FilterTransformerElement element = elements.get(index);
                Method setScript;
                try {
                    setScript = element.getClass().getMethod("setScript", String.class);
                } catch (NoSuchMethodException e) {
                    throw new IllegalArgumentException("Element '" + element.getName() + "' is van type " + element.getType() + " en heeft geen bewerkbaar script. Alleen JavaScript-elementen kunnen worden aangepast.");
                }
                location = type + " " + index + " '" + element.getName() + "' van connector " + metaDataId + " '" + connector.getName() + "'";
                oldScript = element.getScript(false);
                apply = ch -> {
                    try {
                        setScript.invoke(element, script);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                    return null;
                };
                break;
            }
            default:
                throw new IllegalArgumentException("Onbekend scriptType '" + type + "'. Kies uit: " + String.join(", ", SCRIPT_TYPES));
        }

        // Claude only ever saw the masked script, so a "***" it copied back would overwrite real content.
        boolean suspicious = script.contains(Masker.MASK) && (oldScript == null || !oldScript.contains(Masker.MASK));
        String detail = "Channel '" + current.getName() + "', " + location + ".\n"
                + (suspicious ? "LET OP: het nieuwe script bevat '" + Masker.MASK + "'. Claude zag het script gemaskeerd; controleer of daar geen echte waarde (bijv. een nummer of HL7-voorbeeld) verloren gaat.\n" : "")
                + "De channel wordt opgeslagen (nieuwe revisie) maar niet gedeployd. Heb je deze channel open in de editor, laad hem daarna opnieuw.\n\n"
                + "--- huidig script\n" + (oldScript == null ? "" : oldScript) + "\n\n+++ nieuw script\n" + script;
        int revision = current.getRevision();
        return new PreparedAction("Script aanpassen", detail, ctx -> {
            Channel latest = channelController.getChannelById(current.getId());
            if (latest == null || latest.getRevision() != revision) {
                return "Niet opgeslagen: de channel is intussen gewijzigd (revisie " + (latest == null ? "?" : latest.getRevision()) + " in plaats van " + revision + "). Haal de channel opnieuw op.";
            }
            apply.apply(copy);
            boolean saved = channelController.updateChannel(copy, ctx, false, Calendar.getInstance());
            return saved ? "Script opgeslagen in channel '" + current.getName() + "' (nieuwe revisie). Deploy de channel om de wijziging actief te maken." : "Niet opgeslagen: de channel is intussen door iemand anders gewijzigd.";
        });
    }

    // ------------------------------------------------------------------ helpers

    private void read(String name, String description, Map<String, Object> properties, ReadTool tool, String... required) {
        specs.add(new Spec(name, false, description, properties, required));
        readTools.put(name, tool);
    }

    private void action(String name, String description, Map<String, Object> properties, ActionTool tool, String... required) {
        specs.add(new Spec(name, true, description, properties, required));
        actionTools.put(name, tool);
    }

    /** Accepts a channel ID or (case-insensitive) channel name. */
    private Channel channel(JsonNode in) {
        String idOrName = text(in, "channel");
        Channel byId = channelController.getChannelById(idOrName);
        if (byId != null) {
            return byId;
        }
        List<Channel> all = channelController.getChannels(null);
        for (Channel c : all) {
            if (c.getName().equalsIgnoreCase(idOrName.trim())) {
                return c;
            }
        }
        List<String> names = new ArrayList<>();
        for (Channel c : all) {
            names.add(c.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        throw new IllegalArgumentException("Channel '" + idOrName + "' niet gevonden. Beschikbaar: " + String.join(", ", names));
    }

    private static List<Connector> connectors(Channel channel) {
        List<Connector> list = new ArrayList<>();
        list.add(channel.getSourceConnector());
        list.addAll(channel.getDestinationConnectors());
        return list;
    }

    static String text(JsonNode in, String field) {
        JsonNode node = in.get(field);
        if (node == null || node.isNull() || node.asText().isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + field + "' ontbreekt.");
        }
        return node.asText();
    }

    /** Parses an ISO date or date-time; a plain date means midnight in the server's time zone. */
    static Calendar date(JsonNode in, String field) {
        String value = in.path(field).asText("");
        if (value.isBlank()) {
            return null;
        }
        ZonedDateTime zdt;
        try {
            zdt = OffsetDateTime.parse(value).toZonedDateTime();
        } catch (DateTimeParseException e) {
            try {
                zdt = LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault());
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("Ongeldige datum in '" + field + "': " + value + ". Gebruik ISO, bijv. 2026-09-23T00:00:00+02:00.");
            }
        }
        return GregorianCalendar.from(zdt);
    }

    private static String format(Calendar c) {
        if (c == null) {
            return "?";
        }
        return c.toInstant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static String firstLines(String text, int max) {
        String[] lines = text.split("\\r?\\n|\\r");
        if (lines.length <= max) {
            return text.trim();
        }
        return String.join("\n", Arrays.copyOf(lines, max)).trim() + "\n    [... " + (lines.length - max) + " regels]";
    }

    // JSON schema builders

    private static Map<String, Object> props(Object... nameSchemaPairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < nameSchemaPairs.length; i += 2) {
            map.put((String) nameSchemaPairs[i], nameSchemaPairs[i + 1]);
        }
        return map;
    }

    private static Map<String, Object> str(String description) {
        return schema("string", description);
    }

    private static Map<String, Object> bool(String description) {
        return schema("boolean", description);
    }

    private static Map<String, Object> integer(String description) {
        return schema("integer", description);
    }

    private static Map<String, Object> enumStr(String description, String... values) {
        Map<String, Object> m = schema("string", description);
        m.put("enum", Arrays.asList(values));
        return m;
    }

    private static Map<String, Object> arr(Map<String, Object> items, String description) {
        Map<String, Object> m = schema("array", description);
        m.put("items", items);
        return m;
    }

    private static Map<String, Object> schema(String type, String description) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("description", description);
        return m;
    }
}
