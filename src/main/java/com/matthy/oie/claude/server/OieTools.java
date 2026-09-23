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
            throw new IllegalArgumentException("Unknown tool: " + name);
        }
        return clip(masker.mask(tool.run(input)));
    }

    public PreparedAction prepare(String name, JsonNode input) throws Exception {
        ActionTool tool = actionTools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown action: " + name);
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
        return text.substring(0, MAX_RESULT_CHARS) + "\n\n[... truncated: " + (text.length() - MAX_RESULT_CHARS) + " characters omitted. Narrow the query.]";
    }

    // ------------------------------------------------------------------ read tools

    private void registerReadTools() {
        read("oie_server_info", "Version, status, JVM/OS info, memory and disk space of the OIE server.", props(), in -> serverInfo());

        read("oie_list_channels", "Table of all channels with deploy state (STARTED/STOPPED/PAUSED/UNDEPLOYED) and counters (received/filtered/sent/queued/error), channels with errors first. Start here.", props(
                "includeUndeployed", bool("Also show undeployed channels (default true)"),
                "includeConnectors", bool("Also show counters per source/destination (default false)")), in -> listChannels(in.path("includeUndeployed").asBoolean(true), in.path("includeConnectors").asBoolean(false)));

        read("oie_get_channel", "Full channel configuration as OIE export XML: connectors, filters, transformers (JavaScript), data type properties and deploy/undeploy/pre/postprocessor scripts.", props(
                "channel", str("Channel ID (UUID) or channel name")), in -> ObjectXMLSerializer.getInstance().serialize(channel(in)), "channel");

        read("oie_channel_scripts", "Overview of all scripts in a channel with their location (connector metaDataId and index), so you know what oie_update_channel_script should change.", props(
                "channel", str("Channel ID (UUID) or channel name")), in -> channelScripts(channel(in)), "channel");

        read("oie_channel_statistics", "Counters per connector (source = metaDataId 0, destinations 1..n) for one channel.", props(
                "channel", str("Channel ID (UUID) or channel name")), in -> channelStatistics(channel(in)), "channel");

        read("oie_search_messages", "Searches messages in a channel by status, period or text. Returns per message the ID, date, status per connector and error. With includeContent also the content (masked).", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "statuses", arr(enumStr("Message status", MESSAGE_STATUSES), "Bijv. [\"ERROR\"] of [\"QUEUED\"]"),
                "startDate", str("ISO date/time, e.g. 2026-09-23T00:00:00+02:00"),
                "endDate", str("ISO date/time"),
                "textSearch", str("Searches the content (can be slow)"),
                "onlyWithErrors", bool("Only messages with an error"),
                "includeContent", bool("Also return the content (default false)"),
                "limit", integer("Maximum number of messages, 1-50 (default 20)"),
                "offset", integer("Number of messages to skip (default 0)")), this::searchMessages, "channel");

        read("oie_get_message", "One message with all connector messages: raw, transformed, encoded, sent, response, maps and errors (patient data masked).", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "messageId", integer("Message ID"),
                "metaDataIds", arr(integer("Connector"), "Limit to these connectors (0 = source)")), this::getMessage, "channel", "messageId");

        read("oie_events", "Audit/server events (deploys, errors, logins), newest first. Filter by level, name and period.", props(
                "level", enumStr("Level", "INFORMATION", "WARNING", "ERROR"),
                "name", str("Filter by (part of) the event name"),
                "startDate", str("ISO date/time"),
                "endDate", str("ISO date/time"),
                "limit", integer("Maximum number of events, 1-200 (default 50)")), this::events);

        read("oie_server_log", "Most recent lines from the OIE server log. Useful for deploy and script errors.", props(
                "fetchSize", integer("Number of lines, 1-500 (default 100)")), in -> serverLog(in.path("fetchSize").asInt(100)));

        read("oie_list_code_templates", "Code template libraries with their templates (shared functions) and the channels they are linked to.", props(
                "includeCode", bool("Also return the code (default false)")), in -> codeTemplates(in.path("includeCode").asBoolean(false)));

        read("oie_get_code_template", "One code template including its code.", props(
                "codeTemplateId", str("ID of the code template")), in -> ObjectXMLSerializer.getInstance().serialize(codeTemplateController.getCodeTemplateById(text(in, "codeTemplateId"))), "codeTemplateId");

        read("oie_configuration_map", "Keys of the Configuration Map. Values are never returned because they often contain passwords or paths.", props(), in -> String.join("\n", new TreeMap<>(configurationController.getConfigurationMap()).keySet()));
    }

    private String serverInfo() {
        Runtime rt = Runtime.getRuntime();
        StringBuilder sb = new StringBuilder();
        sb.append("Version: ").append(configurationController.getServerVersion()).append(" (build ").append(configurationController.getBuildDate()).append(")\n");
        sb.append("Server-ID: ").append(configurationController.getServerId()).append('\n');
        sb.append("Status: ").append(configurationController.getStatus()).append(" (0 = running)\n");
        sb.append("Java: ").append(System.getProperty("java.version")).append(" (").append(System.getProperty("java.vendor")).append(")\n");
        sb.append("OS: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version")).append(' ').append(System.getProperty("os.arch")).append('\n');
        sb.append("CPUs: ").append(rt.availableProcessors()).append('\n');
        long used = rt.totalMemory() - rt.freeMemory();
        sb.append(String.format(Locale.ROOT, "Heap: %d MB used, %d MB allocated, %d MB max%n", used >> 20, rt.totalMemory() >> 20, rt.maxMemory() >> 20));
        File appDir = new File(System.getProperty("user.dir"));
        sb.append(String.format(Locale.ROOT, "Disk (%s): %d GB free of %d GB%n", appDir.getAbsolutePath(), appDir.getUsableSpace() >> 30, appDir.getTotalSpace() >> 30));
        sb.append("Deployed channels: ").append(engineController.getDeployedIds().size()).append(" of ").append(channelController.getChannelIds().size()).append('\n');
        return sb.toString();
    }

    private String listChannels(boolean includeUndeployed, boolean includeConnectors) {
        List<DashboardStatus> statuses = new ArrayList<>(engineController.getChannelStatusList(null, includeUndeployed));
        if (statuses.isEmpty()) {
            return "No channels found.";
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
        sb.append('\n').append(statuses.size()).append(" channels, ").append(started).append(" started, ").append(totalErrors).append(" errors in ").append(withErrors).append(" channel(s).\n");
        sb.append("Counters are since the last statistics reset. 'Sent' counts per destination.");
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
            return "Channel '" + channel.getName() + "' is not deployed; there are no current counters.";
        }
        StringBuilder sb = new StringBuilder("Channel '" + channel.getName() + "' (" + status.getState() + ")\n\n| Connector | metaDataId | Status | Received | Filtered | Sent | Queued | Errors |\n|---|---:|---|---:|---:|---:|---:|---:|\n");
        List<DashboardStatus> rows = new ArrayList<>();
        rows.add(status);
        if (status.getChildStatuses() != null) {
            rows.addAll(status.getChildStatuses());
        }
        for (DashboardStatus s : rows) {
            sb.append("| ").append(s == status ? "(channel total)" : s.getName()).append(" | ").append(s.getMetaDataId() == null ? "" : s.getMetaDataId()).append(" | ").append(s.getState()).append(" | ").append(count(s, Status.RECEIVED)).append(" | ").append(count(s, Status.FILTERED)).append(" | ").append(count(s, Status.SENT)).append(" | ").append(s.getQueued() == null ? 0 : s.getQueued()).append(" | ").append(count(s, Status.ERROR)).append(" |\n");
        }
        return sb.toString();
    }

    private String channelScripts(Channel channel) throws Exception {
        StringBuilder sb = new StringBuilder("Scripts in channel '" + channel.getName() + "' (revision " + channel.getRevision() + ")\n\n");
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
        sb.append("\nOnly elements of type JavaScript can be changed with oie_update_channel_script.");
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
            return "(empty)";
        }
        return script.split("\n", -1).length + " lines";
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
            return total + " message(s) found, " + messages.size() + " shown (offset " + offset + ").\n\n" + ObjectXMLSerializer.getInstance().serialize(messages);
        }
        StringBuilder sb = new StringBuilder(total + " message(s) found, " + messages.size() + " shown (offset " + offset + ").\n\n");
        for (Message m : messages) {
            sb.append("Message ").append(m.getMessageId()).append(" — received ").append(format(m.getReceivedDate())).append('\n');
            for (Map.Entry<Integer, ConnectorMessage> e : new TreeMap<>(m.getConnectorMessages()).entrySet()) {
                ConnectorMessage cm = e.getValue();
                sb.append("  [").append(e.getKey()).append("] ").append(cm.getConnectorName()).append(": ").append(cm.getStatus());
                String error = cm.getProcessingError() != null ? cm.getProcessingError() : cm.getResponseError();
                if (error != null && !error.isBlank()) {
                    sb.append(" — error: ").append(firstLines(error, 6));
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
            return "Message " + messageId + " not found in channel '" + channel.getName() + "'.";
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
            return "No events found.";
        }
        StringBuilder sb = new StringBuilder();
        for (ServerEvent e : events) {
            sb.append(format(e.getEventTime())).append("  ").append(e.getLevel()).append("  ").append(e.getName()).append("  (").append(e.getOutcome()).append(", user ").append(e.getUserId()).append(")\n");
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
            return "The Server Log extension is not installed or not started.";
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
        return sb.length() == 0 ? "The server log is empty." : sb.toString();
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
            sb.append(lib.isIncludeNewChannels() ? " — also for new channels" : "").append("\n  linked to: ").append(enabled.isEmpty() ? "(no channels)" : String.join(", ", enabled)).append('\n');
            if (lib.getCodeTemplates() != null) {
                for (CodeTemplate t : lib.getCodeTemplates()) {
                    sb.append("  - ").append(t.getName()).append(" (").append(t.getId()).append(")\n");
                }
            }
        }
        return sb.length() == 0 ? "No code template libraries." : sb.toString();
    }

    // ------------------------------------------------------------------ action tools

    private void registerActionTools() {
        Map<String, Object> channelOnly = props("channel", str("Channel ID (UUID) or channel name"));

        action("oie_deploy_channel", "Deploys (or redeploys) a channel. Requires approval by the user.", channelOnly, in -> {
            Channel c = channel(in);
            return new PreparedAction("Deploy channel", "Deploy channel '" + c.getName() + "' (" + c.getId() + ")" + (engineController.isDeployed(c.getId()) ? " (already deployed: it will be redeployed)" : "") + ".", ctx -> {
                ErrorTaskHandler handler = new ErrorTaskHandler();
                engineController.deployChannels(Collections.singleton(c.getId()), ctx, handler, new DebugOptions());
                return handled(handler, "Channel '" + c.getName() + "' has been deployed.");
            });
        }, "channel");

        action("oie_undeploy_channel", "Undeploys a channel. Requires approval by the user.", channelOnly, in -> {
            Channel c = channel(in);
            return new PreparedAction("Undeploy channel", "Undeploy channel '" + c.getName() + "' (" + c.getId() + "). The channel will no longer process messages.", ctx -> {
                ErrorTaskHandler handler = new ErrorTaskHandler();
                engineController.undeployChannels(Collections.singleton(c.getId()), ctx, handler);
                return handled(handler, "Channel '" + c.getName() + "' has been undeployed.");
            });
        }, "channel");

        channelStateAction("oie_start_channel", "Starts a deployed channel.", "Start channel", "started", (ids, h) -> engineController.startChannels(ids, h));
        channelStateAction("oie_stop_channel", "Stops a deployed channel.", "Stop channel", "stopped", (ids, h) -> engineController.stopChannels(ids, h));
        channelStateAction("oie_pause_channel", "Pauses a deployed channel (the source stops, the destinations work off their queues).", "Pause channel", "paused", (ids, h) -> engineController.pauseChannels(ids, h));
        channelStateAction("oie_resume_channel", "Resumes a paused channel.", "Resume channel", "resumed", (ids, h) -> engineController.resumeChannels(ids, h));

        action("oie_reset_statistics", "Resets the counters (received/filtered/sent/error) of a channel to zero. Requires approval.", channelOnly, in -> {
            Channel c = channel(in);
            return new PreparedAction("Reset statistics", "Reset the counters of channel '" + c.getName() + "' to zero (received, filtered, sent and error). The messages themselves are kept.", ctx -> {
                List<Integer> metaDataIds = new ArrayList<>();
                metaDataIds.add(null);
                for (Connector connector : connectors(c)) {
                    metaDataIds.add(connector.getMetaDataId());
                }
                Map<String, List<Integer>> map = new HashMap<>();
                map.put(c.getId(), metaDataIds);
                channelController.resetStatistics(map, new HashSet<>(Arrays.asList(Status.RECEIVED, Status.FILTERED, Status.SENT, Status.ERROR)));
                return "Statistics of '" + c.getName() + "' have been reset.";
            });
        }, "channel");

        action("oie_reprocess_message", "Reprocesses an existing message through the channel. Requires approval.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "messageId", integer("Message ID"),
                "replace", bool("Overwrite the existing message instead of creating a new one (default false)")), in -> {
            Channel c = channel(in);
            long messageId = in.path("messageId").asLong();
            boolean replace = in.path("replace").asBoolean(false);
            return new PreparedAction("Reprocess message", "Reprocess message " + messageId + " in channel '" + c.getName() + "'" + (replace ? ", overwriting the existing message." : " as a new message."), ctx -> {
                MessageFilter filter = new MessageFilter();
                filter.setMinMessageId(messageId);
                filter.setMaxMessageId(messageId);
                messageController.reprocessMessages(c.getId(), filter, replace, null);
                return "Message " + messageId + " has been reprocessed. Use oie_search_messages to find the result.";
            });
        }, "channel", "messageId");

        action("oie_send_message", "Sends a raw message to the source of a deployed channel, for example to test a fix. Requires approval.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "message", str("Raw message in the channel's inbound data type (GDT, HL7 ER7, XML, ...)")), in -> {
            Channel c = channel(in);
            String raw = text(in, "message");
            return new PreparedAction("Send message", "Send this message to channel '" + c.getName() + "':\n\n" + raw, ctx -> {
                DispatchResult result = engineController.dispatchRawMessage(c.getId(), new RawMessage(raw), false, true);
                return result == null ? "The message was not processed; is the channel started?" : "Message processed as message ID " + result.getMessageId() + ".";
            });
        }, "channel", "message");

        action("oie_update_channel_script", "Replaces one JavaScript script in a channel: the deploy, undeploy, preprocessor or postprocessor script, or a JavaScript filter rule or transformer step (see oie_channel_scripts for metaDataId and index). Saves the channel but does not deploy it. Requires approval.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "scriptType", enumStr("Kind of script", SCRIPT_TYPES),
                "metaDataId", integer("Connector (0 = source), only for filter_rule/transformer_step/response_transformer_step"),
                "index", integer("Position of the rule/step (from 0), only for filter_rule/transformer_step/response_transformer_step"),
                "script", str("The complete new script")), this::prepareScriptUpdate, "channel", "scriptType", "script");
    }

    private interface ChannelTask {
        void run(Set<String> channelIds, ErrorTaskHandler handler);
    }

    private void channelStateAction(String name, String description, String title, String done, ChannelTask task) {
        action(name, description + " Requires approval by the user.", props("channel", str("Channel ID (UUID) or channel name")), in -> {
            Channel c = channel(in);
            if (!engineController.isDeployed(c.getId())) {
                throw new IllegalStateException("Channel '" + c.getName() + "' is not deployed.");
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
            return "Failed: " + (e == null ? "unknown error" : e.getMessage());
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
                location = "deploy script";
                oldScript = copy.getDeployScript();
                apply = ch -> { ch.setDeployScript(script); return null; };
                break;
            case "undeploy":
                location = "undeploy script";
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
                Connector connector = connectors(copy).stream().filter(c -> c.getMetaDataId() == metaDataId).findFirst().orElseThrow(() -> new IllegalArgumentException("Connector with metaDataId " + metaDataId + " does not exist in '" + current.getName() + "'."));
                List<? extends FilterTransformerElement> elements;
                if (type.equals("filter_rule")) {
                    elements = connector.getFilter().getElements();
                } else if (type.equals("transformer_step")) {
                    elements = connector.getTransformer().getElements();
                } else {
                    elements = connector.getResponseTransformer() == null ? Collections.emptyList() : connector.getResponseTransformer().getElements();
                }
                if (index < 0 || index >= elements.size()) {
                    throw new IllegalArgumentException("Index " + index + " does not exist; connector '" + connector.getName() + "' has " + elements.size() + " " + type + " element(s).");
                }
                FilterTransformerElement element = elements.get(index);
                Method setScript;
                try {
                    setScript = element.getClass().getMethod("setScript", String.class);
                } catch (NoSuchMethodException e) {
                    throw new IllegalArgumentException("Element '" + element.getName() + "' is of type " + element.getType() + " and has no editable script. Only JavaScript elements can be changed.");
                }
                location = type + " " + index + " '" + element.getName() + "' of connector " + metaDataId + " '" + connector.getName() + "'";
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
                throw new IllegalArgumentException("Unknown scriptType '" + type + "'. Choose from: " + String.join(", ", SCRIPT_TYPES));
        }

        // Claude only ever saw the masked script, so a "***" it copied back would overwrite real content.
        boolean suspicious = script.contains(Masker.MASK) && (oldScript == null || !oldScript.contains(Masker.MASK));
        String detail = "Channel '" + current.getName() + "', " + location + ".\n"
                + (suspicious ? "WARNING: the new script contains '" + Masker.MASK + "'. Claude saw the script masked; check that no real value (e.g. a number or HL7 sample) is lost.\n" : "")
                + "The channel is saved (new revision) but not deployed. If you have this channel open in the editor, reload it afterwards.\n\n"
                + "--- current script\n" + (oldScript == null ? "" : oldScript) + "\n\n+++ new script\n" + script;
        int revision = current.getRevision();
        return new PreparedAction("Change script", detail, ctx -> {
            Channel latest = channelController.getChannelById(current.getId());
            if (latest == null || latest.getRevision() != revision) {
                return "Not saved: the channel has changed in the meantime (revision " + (latest == null ? "?" : latest.getRevision()) + " instead of " + revision + "). Fetch the channel again.";
            }
            apply.apply(copy);
            boolean saved = channelController.updateChannel(copy, ctx, false, Calendar.getInstance());
            return saved ? "Script saved in channel '" + current.getName() + "' (new revision). Deploy the channel to activate the change." : "Not saved: someone else changed the channel in the meantime.";
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
        throw new IllegalArgumentException("Channel '" + idOrName + "' not found. Available: " + String.join(", ", names));
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
            throw new IllegalArgumentException("Parameter '" + field + "' is missing.");
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
                throw new IllegalArgumentException("Invalid date in '" + field + "': " + value + ". Use ISO, e.g. 2026-09-23T00:00:00+02:00.");
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
        return String.join("\n", Arrays.copyOf(lines, max)).trim() + "\n    [... " + (lines.length - max) + " lines]";
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
