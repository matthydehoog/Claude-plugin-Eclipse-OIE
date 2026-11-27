/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.io.File;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import org.apache.ibatis.session.SqlSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.mirth.connect.donkey.model.channel.CronProperty;
import com.mirth.connect.donkey.model.channel.DebugOptions;
import com.mirth.connect.donkey.model.channel.DeployedState;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.channel.PollConnectorProperties;
import com.mirth.connect.donkey.model.channel.SourceConnectorPropertiesInterface;
import com.mirth.connect.donkey.model.message.ConnectorMessage;
import com.mirth.connect.donkey.model.message.Message;
import com.mirth.connect.donkey.model.message.RawMessage;
import com.mirth.connect.donkey.model.message.Status;
import com.mirth.connect.donkey.server.Donkey;
import com.mirth.connect.donkey.server.channel.DispatchResult;
import com.mirth.connect.donkey.server.data.DonkeyDao;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.ChannelMetadata;
import com.mirth.connect.model.ChannelProperties;
import com.mirth.connect.model.ChannelPruningSettings;
import com.mirth.connect.model.ChannelTag;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.DashboardStatus;
import com.mirth.connect.model.FilterTransformerElement;
import com.mirth.connect.model.Rule;
import com.mirth.connect.model.ServerEvent;
import com.mirth.connect.model.ServerEventContext;
import com.mirth.connect.model.Step;
import com.mirth.connect.model.Transformer;
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
import com.mirth.connect.server.controllers.ScriptController;
import com.mirth.connect.server.util.SqlConfig;

/**
 * The tools Claude can call. Read tools run straight away inside the chat job. Action tools only
 * describe what they would do; they run after the user approves them in the Administrator.
 */
public class OieTools {

    /** Longest tool result sent back to Claude, in characters. */
    static final int MAX_RESULT_CHARS = 60_000;

    private static final String[] MESSAGE_STATUSES = { "RECEIVED", "FILTERED", "TRANSFORMED", "SENT", "QUEUED", "ERROR", "PENDING" };
    private static final String[] GLOBAL_SCRIPT_TYPES = { "deploy", "undeploy", "preprocessor", "postprocessor" };
    private static final String[] SCRIPT_TYPES = { "deploy", "undeploy", "preprocessor", "postprocessor", "filter_rule", "transformer_step", "response_transformer_step" };

    /**
     * The OIE operation each tool corresponds to, i.e. what the Administrator itself would call for
     * the same data or change. The user must be authorized for it (their own role, via the RBAC
     * extension when installed); Claude never gets more than the user could see or do.
     * Tools without an entry (server info) need no permission, like OIE's own version/status calls.
     */
    static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("oie_list_channels", "getChannelStatusList"),
            Map.entry("oie_get_channel", "getChannel"),
            Map.entry("oie_channel_scripts", "getChannel"),
            Map.entry("oie_channel_overview", "getChannel"),
            Map.entry("oie_channel_statistics", "getStatistics"),
            Map.entry("oie_search_messages", "searchMessages"),
            Map.entry("oie_queues_and_storage", "searchMessages"),
            Map.entry("oie_get_message", "getMessageContent"),
            Map.entry("oie_events", "getEvents"),
            Map.entry("oie_server_log", "getMirthServerLogs"),
            Map.entry("oie_list_code_templates", "getCodeTemplateLibraries"),
            Map.entry("oie_get_code_template", "getCodeTemplates"),
            Map.entry("oie_global_scripts", "getGlobalScripts"),
            Map.entry("oie_configuration_map", "getConfigurationMap"),
            Map.entry("oie_find_usages", "getChannel"),
            Map.entry("oie_check_channel", "getChannel"),
            Map.entry("oie_deploy_channel", "deployChannels"),
            Map.entry("oie_undeploy_channel", "undeployChannels"),
            Map.entry("oie_start_channel", "startChannels"),
            Map.entry("oie_stop_channel", "stopChannels"),
            Map.entry("oie_pause_channel", "pauseChannels"),
            Map.entry("oie_resume_channel", "resumeChannels"),
            Map.entry("oie_reset_statistics", "clearStatistics"),
            Map.entry("oie_reprocess_message", "reprocessMessage"),
            Map.entry("oie_send_message", "processMessages"),
            Map.entry("oie_update_channel_script", "updateChannel"),
            Map.entry("oie_update_channel_description", "updateChannel"),
            Map.entry("oie_update_global_script", "setGlobalScripts"),
            Map.entry("oie_update_filter", "updateChannel"),
            Map.entry("oie_update_transformer", "updateChannel"),
            Map.entry("oie_create_channel", "createChannel"));

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
        /** New or changed JavaScript by label, compiled before the user sees the proposal. */
        final Map<String, String> scripts = new LinkedHashMap<>();
        /** The changes as a diff (see {@link LineDiff}), shown by default in the approval dialog; null when there is none. */
        String changes;

        PreparedAction(String title, String detail, ActionRunner runner) {
            this.title = title;
            this.detail = detail;
            this.runner = runner;
        }

        PreparedAction withScript(String label, String script) {
            if (script != null && !script.isBlank()) {
                scripts.put(label, script);
            }
            return this;
        }

        PreparedAction withChanges(String diff) {
            this.changes = diff;
            return this;
        }

        /** The same action with a warning above the detail and the changes, keeping the scripts. */
        PreparedAction withWarning(String warning) {
            PreparedAction copy = new PreparedAction(title, warning + detail, runner);
            copy.scripts.putAll(scripts);
            copy.changes = changes == null ? null : warning + changes;
            return copy;
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
    private final ScriptController scriptController;

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
        scriptController = f.createScriptController();
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

        read("oie_channel_overview", "A readable overview of one channel, shorter than oie_get_channel: per connector its type, data types, queue and settings (secrets hidden), "
                + "the filter rules and transformer steps with the script OIE generates for each (so you see what a Mapper or Rule Builder element really does), "
                + "the channel scripts, the code template libraries and the Configuration Map keys the channel uses. Use it to explain or document a channel. "
                + "To document a channel, write Markdown with these sections: Purpose, Source, Filter, Destinations (with when each runs), Field mappings (a table: source field, target, transformation), "
                + "Scripts and code templates, Configuration Map keys, Open points (what you noticed, such as disabled rules). Describe in plain language, not code; never include a password or other secret. "
                + "The user saves your answer as a separate file (Save answer), so make the answer only the documentation: start with a level-1 heading '# <channel name>', with no introduction before it and no question or remark after it. "
                + "For a large channel, ask for a few connectors at a time with metaDataIds.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "metaDataIds", arr(integer("Connector"), "Only these connectors (0 = source); default all")), this::channelOverview, "channel");

        read("oie_channel_statistics", "Counters per connector (source = metaDataId 0, destinations 1..n) for one channel.", props(
                "channel", str("Channel ID (UUID) or channel name")), in -> channelStatistics(channel(in)), "channel");

        read("oie_queues_and_storage", "Queues and message storage, to find slow-burning problems. Per source/destination: queue size, the oldest queued message and its age, send attempts, the last error, "
                + "and whether queueing and retries are on. Per channel: number of stored messages, an estimate of their size in the database, the oldest stored message, the pruning settings (or none) and the storage mode. "
                + "Also the database type and size and whether the Data Pruner runs. Reads counts, dates and settings only, never message content. "
                + "Point out destinations that are stuck (stopped, or a queue that has not moved for a long time, with the error) and channels without pruning that keep growing. "
                + "You can propose starting or resuming a channel with the action tools; pruning settings are changed by the user: channel editor, tab Summary, section Message Pruning (Prune metadata older than ... days; queued and errored messages are kept unless Prune Errored Messages is ticked), and they only apply while the Data Pruner runs.", props(
                "channel", str("Only this channel (ID or name), with the queue settings of every connector; default all channels")), this::queuesAndStorage);

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

        read("oie_get_message", "One message with all connector messages: raw, transformed, encoded, sent, response, maps and errors (patient data masked; map and custom metadata values are always masked as ***, only their keys are readable).", props(
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

        read("oie_global_scripts", "The global Deploy, Undeploy, Preprocessor and Postprocessor scripts, which run for every channel (in addition to each channel's own scripts).", props(), in -> globalScripts());

        read("oie_configuration_map", "Keys of the Configuration Map. Values are never returned because they often contain passwords or paths.", props(), in -> String.join("\n", new TreeMap<>(configurationController.getConfigurationMap()).keySet()));

        read("oie_check_channel", "Checks one channel, or every channel when no channel is given, for known weak spots, in one call: "
                + "credentials typed into scripts or connector settings (reported by location only, never the value), calls to code template functions whose library is not linked to the channel, "
                + "TCP/HTTP/database/web service/SMTP/JMS/DICOM destinations without a queue, message storage set to Development or pruning switched off, "
                + "logger.info/debug/trace in scripts that run for every message, and disabled destinations, filter rules and transformer steps. "
                + "Use it when asked to check or review a channel or the server for weak spots; explain each finding and propose fixes where an action can make them.", props(
                "channel", str("Channel ID or name; omit to check every channel")), this::checkChannels);

        read("oie_find_usages", "Finds where something is used, in one call over all channels (channel scripts, filter rules, transformer steps, response transformers, connector settings), "
                + "code templates and global scripts. Returns only locations (channel, connector, element, line or setting) and the form of each use, never code or values. "
                + "Use it before changing or removing something, to answer 'what breaks if I change X?', and to see which channels send to a channel. "
                + "kind: config = a Configuration Map key ($cfg, configurationMap.get, ${key} in settings); map = a variable in globalMap, globalChannelMap, channelMap, connectorMap, responseMap or sourceMap "
                + "(get/put and $g, $gc, $c, $co, $r, $s, $()); function = a code template function (definition and calls); channel = a channel by ID or name (Channel Writer, router.routeMessage, routeMessageByChannelId).", props(
                "kind", enumStr("What to look for", Usages.KINDS),
                "name", str("The key, variable or function name (case-sensitive), or the channel's ID or name")), this::findUsages, "kind", "name");
    }

    private String checkChannels(JsonNode in) throws Exception {
        String name = in.path("channel").asText("").trim();
        List<Channel> channels = name.isEmpty() ? channelController.getChannels(null) : List.of(channel(in));
        Map<String, ChannelMetadata> metadata = configurationController.getChannelMetadata();
        List<CodeTemplateLibrary> libraries = codeTemplateController.getLibraries(null, true);
        // The functions each library defines.
        Map<CodeTemplateLibrary, Set<String>> functions = new LinkedHashMap<>();
        for (CodeTemplateLibrary lib : libraries) {
            Set<String> names = new LinkedHashSet<>();
            if (lib.getCodeTemplates() != null) {
                for (com.mirth.connect.model.codetemplates.CodeTemplate t : lib.getCodeTemplates()) {
                    names.addAll(ChannelCheck.functions(t.getCode()));
                }
            }
            functions.put(lib, names);
        }
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        List<Map.Entry<String, List<ChannelCheck.Finding>>> results = new ArrayList<>();
        for (Channel c : channels) {
            Set<String> linkedFunctions = new HashSet<>();
            Map<String, String> unlinked = new TreeMap<>();
            for (Map.Entry<CodeTemplateLibrary, Set<String>> lib : functions.entrySet()) {
                if (linked(lib.getKey(), c.getId())) {
                    linkedFunctions.addAll(lib.getValue());
                }
            }
            for (Map.Entry<CodeTemplateLibrary, Set<String>> lib : functions.entrySet()) {
                if (!linked(lib.getKey(), c.getId())) {
                    for (String f : lib.getValue()) {
                        if (!linkedFunctions.contains(f)) {
                            unlinked.putIfAbsent(f, lib.getKey().getName());
                        }
                    }
                }
            }
            ChannelMetadata md = metadata == null ? null : metadata.get(c.getId());
            ChannelPruningSettings pruning = md == null ? null : md.getPruningSettings();
            List<ChannelCheck.Finding> findings = ChannelCheck.check(c, pruning == null ? null : pruning.getPruneMetaDataDays(), pruning == null ? null : pruning.getPruneContentDays(),
                    unlinked, serializer::serialize);
            results.add(Map.entry("Channel '" + c.getName() + "' (" + c.getId() + ")" + (md != null && !md.isEnabled() ? ", disabled" : ""), findings));
        }
        return ChannelCheck.report(results);
    }

    /** Whether a code template library is available to a channel: linked to it, or to new channels and not unlinked. */
    private static boolean linked(CodeTemplateLibrary lib, String channelId) {
        boolean linked = lib.getEnabledChannelIds() != null && lib.getEnabledChannelIds().contains(channelId);
        boolean byDefault = lib.isIncludeNewChannels() && (lib.getDisabledChannelIds() == null || !lib.getDisabledChannelIds().contains(channelId));
        return linked || byDefault;
    }

    private String findUsages(JsonNode in) throws Exception {
        String kind = text(in, "kind");
        String name = text(in, "name").trim();
        List<Channel> channels = channelController.getChannels(null);
        String channelId = null;
        if (Usages.CHANNEL.equals(kind)) {
            for (Channel c : channels) {
                if (c.getId().equals(name) || c.getName().equalsIgnoreCase(name)) {
                    channelId = c.getId();
                    name = c.getName();
                    break;
                }
            }
        }
        Usages usages = new Usages(kind, name, channelId);
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        List<Usages.Source> sources = new ArrayList<>();
        for (Channel c : channels) {
            String group = "Channel '" + c.getName() + "' (" + c.getId() + ")";
            sources.add(new Usages.Source(group, "deploy script", c.getDeployScript(), false, true));
            sources.add(new Usages.Source(group, "undeploy script", c.getUndeployScript(), false, true));
            sources.add(new Usages.Source(group, "preprocessor", c.getPreprocessingScript(), false, true));
            sources.add(new Usages.Source(group, "postprocessor", c.getPostprocessingScript(), false, true));
            for (Connector con : connectors(c)) {
                String where = (con.getMetaDataId() == 0 ? "source" : "destination " + con.getMetaDataId()) + " '" + con.getName() + "'";
                if (con.getProperties() != null) {
                    sources.add(new Usages.Source(group, where + " (" + con.getTransportName() + ")", Masker.unescapeXml(serializer.serialize(con.getProperties())), true, false));
                }
                addUsageSources(sources, group, where + ", filter rule ", con.getFilter() == null ? null : con.getFilter().getElements());
                addUsageSources(sources, group, where + ", transformer step ", con.getTransformer() == null ? null : con.getTransformer().getElements());
                addUsageSources(sources, group, where + ", response transformer step ", con.getResponseTransformer() == null ? null : con.getResponseTransformer().getElements());
            }
        }
        for (CodeTemplateLibrary lib : codeTemplateController.getLibraries(null, true)) {
            if (lib.getCodeTemplates() == null) {
                continue;
            }
            for (com.mirth.connect.model.codetemplates.CodeTemplate t : lib.getCodeTemplates()) {
                sources.add(new Usages.Source("Code template library '" + lib.getName() + "'", "code template '" + t.getName() + "'", t.getCode(), false, true));
            }
        }
        Map<String, String> globals = scriptController.getGlobalScripts();
        for (String type : GLOBAL_SCRIPT_TYPES) {
            sources.add(new Usages.Source("Global scripts", "global " + type + " script", globals == null ? null : globals.get(globalScriptKey(type)), false, true));
        }
        String result = usages.find(sources);
        return Usages.CHANNEL.equals(kind) && channelId == null ? "There is no channel with ID or name '" + name + "'; searched for the name as text.\n\n" + result : result;
    }

    /** One source per element: its script, as OIE generates it; line numbers only for JavaScript elements. */
    private static void addUsageSources(List<Usages.Source> sources, String group, String prefix, List<? extends FilterTransformerElement> elements) {
        if (elements == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            FilterTransformerElement element = elements.get(i);
            boolean javaScript = element.getClass().getSimpleName().startsWith("JavaScript");
            sources.add(new Usages.Source(group, prefix + i + " '" + element.getName() + "'" + (javaScript ? "" : " [" + element.getType() + "]"), generatedScript(element), false, javaScript));
        }
    }

    /** ScriptController's keys for the global scripts, by the scriptType names Claude uses. */
    private static String globalScriptKey(String type) {
        switch (type) {
            case "deploy":
                return ScriptController.DEPLOY_SCRIPT_KEY;
            case "undeploy":
                return ScriptController.UNDEPLOY_SCRIPT_KEY;
            case "preprocessor":
                return ScriptController.PREPROCESSOR_SCRIPT_KEY;
            case "postprocessor":
                return ScriptController.POSTPROCESSOR_SCRIPT_KEY;
            default:
                throw new IllegalArgumentException("Unknown scriptType '" + type + "'. Choose from: " + String.join(", ", GLOBAL_SCRIPT_TYPES));
        }
    }

    private String globalScripts() throws Exception {
        Map<String, String> scripts = scriptController.getGlobalScripts();
        StringBuilder sb = new StringBuilder();
        for (String type : GLOBAL_SCRIPT_TYPES) {
            String script = scripts.get(globalScriptKey(type));
            sb.append("=== global ").append(type).append(" script ===\n").append(script == null || script.isBlank() ? "(empty)" : script).append("\n\n");
        }
        return sb.toString();
    }

    private PreparedAction prepareGlobalScriptUpdate(JsonNode in) throws Exception {
        String type = text(in, "scriptType");
        String key = globalScriptKey(type);
        String script = text(in, "script");
        String oldScript = scriptController.getGlobalScripts().get(key);
        boolean suspicious = script.contains(Masker.MASK) && (oldScript == null || !oldScript.contains(Masker.MASK));
        String head = "Global " + type + " script (runs for every channel).\n"
                + (suspicious ? "WARNING: the new script contains '" + Masker.MASK + "'. The AI Assistant saw the script masked; check that no real value (e.g. a number or HL7 sample) is lost.\n" : "")
                + "Saved now; it takes effect for channels deployed afterwards. If you have Global Scripts open in the Administrator, reload them first.\n\n";
        String detail = head + "--- current script\n" + (oldScript == null ? "" : oldScript) + "\n\n+++ new script\n" + script;
        return new PreparedAction("Change global script", detail, ctx -> {
            Map<String, String> latest = new HashMap<>(scriptController.getGlobalScripts());
            if (!java.util.Objects.equals(latest.get(key), oldScript)) {
                return "Not saved: the global " + type + " script has changed in the meantime. Read it again with oie_global_scripts.";
            }
            latest.put(key, script);
            scriptController.setGlobalScripts(latest);
            return "Global " + type + " script saved. Redeploy channels to use it.";
        }).withScript("global " + type + " script", script).withChanges(head + LineDiff.block("global " + type + " script", oldScript, script));
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

    private String channelOverview(JsonNode in) throws Exception {
        Channel channel = channel(in);
        Set<Integer> only = null;
        if (in.path("metaDataIds").isArray() && in.path("metaDataIds").size() > 0) {
            only = new HashSet<>();
            for (JsonNode n : in.path("metaDataIds")) {
                only.add(n.asInt());
            }
        }
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        ChannelMetadata metadata = configurationController.getChannelMetadata().get(channel.getId());
        List<String> libraries = new ArrayList<>();
        for (CodeTemplateLibrary lib : codeTemplateController.getLibraries(null, false)) {
            if (linked(lib, channel.getId())) {
                libraries.add(lib.getName());
            }
        }
        Map<String, String> configMap = configurationController.getConfigurationMap();
        List<String> keys = ChannelOverview.configKeysUsed(Masker.unescapeXml(serializer.serialize(channel)), configMap == null ? Collections.emptySet() : configMap.keySet());
        Map<String, String> names = new HashMap<>();
        for (Channel c : channelController.getChannels(null)) {
            names.put(c.getId(), c.getName());
        }
        return ChannelOverview.describe(channel, only, metadata == null ? null : metadata.isEnabled(), libraries, keys, names, serializer::serialize);
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

    private String queuesAndStorage(JsonNode in) throws Exception {
        boolean single = !in.path("channel").asText("").isBlank();
        List<Channel> channels = single ? Collections.singletonList(channel(in)) : channelController.getChannels(null);
        Map<String, ChannelMetadata> metadata = configurationController.getChannelMetadata();
        String serverId = configurationController.getServerId();
        String databaseType = configurationController.getDatabaseType();
        List<QueueStorageReport.Queue> queues = new ArrayList<>();
        List<QueueStorageReport.Storage> storage = new ArrayList<>();
        QueueStorageReport.Database db = new QueueStorageReport.Database();
        db.type = databaseType;
        List<String> problems = new ArrayList<>();

        // Autocommit: on PostgreSQL one failed catalog query would otherwise break the rest of the transaction.
        // MyBatis 3.1 sessions are not AutoCloseable.
        SqlSession session = SqlConfig.getInstance().getReadOnlySqlSessionManager().openSession(true);
        try {
            Connection connection = session.getConnection();
            DonkeyDao dao = Donkey.getInstance().getDaoFactory().getDao();
            Map<String, Long> localIds;
            try {
                localIds = dao.getLocalChannelIds();
                for (Channel c : channels) {
                    Long localId = localIds.get(c.getId());
                    DashboardStatus status = engineController.getChannelStatus(c.getId());
                    String channelState = status == null || status.getState() == null ? "UNDEPLOYED" : status.getState().name();
                    try {
                        queues.addAll(channelQueues(c, status, channelState, localId != null, dao, serverId));
                    } catch (Exception e) {
                        problems.add("queues of '" + c.getName() + "': " + e.getMessage());
                    }
                    storage.add(channelStorage(c, metadata.get(c.getId()), channelState, localId, connection, databaseType, problems));
                }
            } finally {
                dao.close();
            }
            db.bytes = queryLong(connection, StorageSql.databaseSize(databaseType), problems, "database size");
        } finally {
            session.close();
        }
        if ("derby".equals(StorageSql.type(databaseType))) {
            File appData = new File(configurationController.getApplicationDataDir());
            db.freeBytes = appData.getUsableSpace();
            db.note = "embedded Derby database in " + appData.getAbsolutePath() + "; free space is of that disk";
        } else {
            db.note = "free space is on the database server; OIE cannot see it";
        }

        Boolean prunerEnabled = null;
        String prunerSchedule = null;
        try {
            if (extensionController.isExtensionEnabled("Data Pruner")) {
                java.util.Properties p = extensionController.getPluginProperties("Data Pruner");
                prunerEnabled = Boolean.parseBoolean(p.getProperty("enabled", "false"));
                prunerSchedule = pollSchedule(p.getProperty("pollingProperties"));
            }
        } catch (Exception e) {
            problems.add("Data Pruner settings: " + e.getMessage());
        }

        String report = QueueStorageReport.describe(queues, storage, db, prunerEnabled, prunerSchedule, single, java.time.Instant.now(), ZoneId.systemDefault());
        if (!problems.isEmpty()) {
            report += "\n\nNot available: " + String.join("; ", problems.subList(0, Math.min(10, problems.size()))) + (problems.size() > 10 ? " (and " + (problems.size() - 10) + " more)" : "") + ".";
        }
        return report;
    }

    private List<QueueStorageReport.Queue> channelQueues(Channel c, DashboardStatus status, String channelState, boolean hasTables, DonkeyDao dao, String serverId) throws Exception {
        Map<Integer, DashboardStatus> byConnector = new HashMap<>();
        if (status != null && status.getChildStatuses() != null) {
            for (DashboardStatus s : status.getChildStatuses()) {
                if (s.getMetaDataId() != null) {
                    byConnector.put(s.getMetaDataId(), s);
                }
            }
        }
        List<QueueStorageReport.Queue> list = new ArrayList<>();
        for (Connector con : connectors(c)) {
            QueueStorageReport.Queue q = new QueueStorageReport.Queue();
            q.channel = c.getName();
            q.channelId = c.getId();
            q.connector = con.getName();
            q.metaDataId = con.getMetaDataId();
            if (con.getProperties() instanceof DestinationConnectorPropertiesInterface) {
                DestinationConnectorProperties d = ((DestinationConnectorPropertiesInterface) con.getProperties()).getDestinationConnectorProperties();
                q.queueEnabled = d.isQueueEnabled();
                q.sendFirst = d.isSendFirst();
                q.retryCount = d.getRetryCount();
                q.retryIntervalMillis = d.getRetryIntervalMillis();
                q.threads = d.getThreadCount();
            } else if (con.getProperties() instanceof SourceConnectorPropertiesInterface) {
                q.queueEnabled = !((SourceConnectorPropertiesInterface) con.getProperties()).getSourceConnectorProperties().isRespondAfterProcessing();
            }
            DashboardStatus cs = byConnector.get(q.metaDataId);
            if (q.metaDataId > 0 && !con.isEnabled()) {
                q.state = "DISABLED";
            } else {
                q.state = cs != null && cs.getState() != null ? cs.getState().name() : channelState;
            }
            if (cs != null && cs.getQueued() != null) {
                q.queued = cs.getQueued();
            } else if (q.metaDataId > 0 && hasTables) {
                q.queued = (long) dao.getConnectorMessageCount(c.getId(), serverId, q.metaDataId, Status.QUEUED);
            } else if (!q.queueEnabled) {
                q.queued = 0L;
            }
            if (q.metaDataId > 0 && q.queued != null && q.queued > 0) {
                oldestQueued(c, q);
            }
            list.add(q);
        }
        return list;
    }

    /** Fills in the oldest queued message of a destination: its date, attempts and error, never its content. */
    private void oldestQueued(Channel c, QueueStorageReport.Queue q) throws Exception {
        MessageFilter filter = new MessageFilter();
        filter.setStatuses(Collections.singleton(Status.QUEUED));
        filter.setIncludedMetaDataIds(Collections.singletonList(q.metaDataId));
        long total = messageController.getMessageCount(filter, c.getId());
        if (total == 0) {
            return;
        }
        // Results come newest first, so the last one is the oldest: the head of the queue.
        List<Message> oldest = messageController.getMessages(filter, c.getId(), false, (int) Math.min(total - 1, Integer.MAX_VALUE), 1);
        if (oldest.isEmpty()) {
            return;
        }
        Message m = oldest.get(0);
        q.oldestQueued = m.getReceivedDate() == null ? null : m.getReceivedDate().toInstant();
        ConnectorMessage cm = m.getConnectorMessages().get(q.metaDataId);
        if (cm != null) {
            q.oldestSendAttempts = cm.getSendAttempts();
            q.lastAttempt = cm.getSendDate() == null ? null : cm.getSendDate().toInstant();
        }
        // Errors are stored with the content; load this one connector message and keep only its error.
        Message full = messageController.getMessageContent(c.getId(), m.getMessageId(), Collections.singletonList(q.metaDataId));
        ConnectorMessage fc = full == null ? null : full.getConnectorMessages().get(q.metaDataId);
        if (fc != null) {
            String error = fc.getProcessingError() != null && !fc.getProcessingError().isBlank() ? fc.getProcessingError() : fc.getResponseError();
            q.lastError = error == null || error.isBlank() ? null : QueueStorageReport.oneLine(error);
        }
    }

    private QueueStorageReport.Storage channelStorage(Channel c, ChannelMetadata md, String channelState, Long localId, Connection connection, String databaseType, List<String> problems) {
        QueueStorageReport.Storage s = new QueueStorageReport.Storage();
        s.channel = c.getName();
        s.channelId = c.getId();
        s.state = channelState;
        ChannelProperties p = c.getProperties();
        if (p != null) {
            s.storageMode = p.getMessageStorageMode() == null ? null : p.getMessageStorageMode().name();
            s.removeContentOnCompletion = p.isRemoveContentOnCompletion();
            s.removeAttachmentsOnCompletion = p.isRemoveAttachmentsOnCompletion();
        }
        ChannelPruningSettings pruning = md == null ? null : md.getPruningSettings();
        if (pruning != null) {
            s.pruneMetaDataDays = pruning.getPruneMetaDataDays();
            s.pruneContentDays = pruning.getPruneContentDays();
            s.archive = pruning.isArchiveEnabled();
            s.pruneErrored = pruning.isPruneErroredMessages();
        }
        if (localId == null) {
            return s; // never deployed: no message tables yet
        }
        try {
            s.messages = messageController.getMessageCount(new MessageFilter(), c.getId());
        } catch (Exception e) {
            problems.add("message count of '" + c.getName() + "': " + e.getMessage());
        }
        if (s.messages != null && s.messages > 0) {
            s.oldest = queryInstant(connection, StorageSql.oldestMessage(localId), problems, "oldest message of '" + c.getName() + "'");
        }
        s.bytes = queryLong(connection, StorageSql.channelSize(databaseType, localId), problems, "size of '" + c.getName() + "'");
        return s;
    }

    private static Long queryLong(Connection connection, String sql, List<String> problems, String what) {
        if (sql == null) {
            return null;
        }
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                long value = rs.getLong(1);
                return rs.wasNull() ? null : value;
            }
        } catch (Exception e) {
            problems.add(what + ": " + e.getMessage());
        }
        return null;
    }

    private static java.time.Instant queryInstant(Connection connection, String sql, List<String> problems, String what) {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toInstant();
            }
        } catch (Exception e) {
            problems.add(what + ": " + e.getMessage());
        }
        return null;
    }

    /** The Data Pruner's schedule in a few words, or null. */
    private static String pollSchedule(String xml) {
        if (xml == null || xml.isBlank()) {
            return null;
        }
        try {
            PollConnectorProperties p = ObjectXMLSerializer.getInstance().deserialize(xml, PollConnectorProperties.class);
            if (p.getPollingType() == null) {
                return null;
            }
            switch (p.getPollingType()) {
                case INTERVAL:
                    return "every " + (p.getPollingFrequency() / 60000) + " min";
                case TIME:
                    return String.format(Locale.ROOT, "daily at %02d:%02d", p.getPollingHour(), p.getPollingMinute());
                case CRON: {
                    List<String> expressions = new ArrayList<>();
                    if (p.getCronJobs() != null) {
                        for (CronProperty cron : p.getCronJobs()) {
                            expressions.add(cron.getExpression());
                        }
                    }
                    return "cron " + String.join(", ", expressions);
                }
                default:
                    return p.getPollingType().name();
            }
        } catch (Exception e) {
            return null;
        }
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

        action("oie_update_global_script", "Replaces one global script (deploy, undeploy, preprocessor or postprocessor); global scripts run for every channel. Takes effect for channels deployed afterwards. Requires approval.", props(
                "scriptType", enumStr("Which global script", GLOBAL_SCRIPT_TYPES),
                "script", str("The complete new script")), this::prepareGlobalScriptUpdate, "scriptType", "script");

        action("oie_update_channel_script","Replaces one JavaScript script in a channel: the deploy, undeploy, preprocessor or postprocessor script, or a JavaScript filter rule or transformer step (see oie_channel_scripts for metaDataId and index). Saves the channel but does not deploy it. Requires approval.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "scriptType", enumStr("Kind of script", SCRIPT_TYPES),
                "metaDataId", integer("Connector (0 = source), only for filter_rule/transformer_step/response_transformer_step"),
                "index", integer("Position of the rule/step (from 0), only for filter_rule/transformer_step/response_transformer_step"),
                "script", str("The complete new script")), this::prepareScriptUpdate, "channel", "scriptType", "script");

        action("oie_update_channel_description", "Replaces the Description field of a channel (shown in the Channels view and the channel editor's Summary tab). "
                + "Write a few sentences of plain text: purpose, source, main destinations and anything notable. If the channel already has a description, "
                + "improve it and keep what its author wrote that is still true. Saves the channel but does not deploy it. Requires approval.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "description", str("The complete new description (plain text)")), this::prepareDescriptionUpdate, "channel", "description");

        action("oie_update_filter", "Replaces the filter rules of one connector with a new list: add, change, remove or reorder rules of any type. "
                + "Give the complete new list in OIE channel XML, in order, as in oie_get_channel: <elements> with for example "
                + "<com.mirth.connect.plugins.rulebuilder.RuleBuilderRule version=\"...\"> (name, sequenceNumber, enabled, operator AND/OR/NONE, field, condition EXISTS/NOT_EXIST/EQUALS/NOT_EQUAL/CONTAINS/NOT_CONTAIN, values of <string>), "
                + "<com.mirth.connect.plugins.javascriptrule.JavaScriptRule> (name, sequenceNumber, enabled, operator, script), "
                + "<com.mirth.connect.plugins.scriptfilerule.ExternalScriptRule> (scriptPath) or <com.mirth.connect.model.IteratorRule>. "
                + "Copy unchanged rules exactly. Write scripts as XML text: escape & as &amp; and < as &lt;, or wrap the script in <![CDATA[...]]>. <elements/> removes all rules. Saves the channel but does not deploy it. Requires approval.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "metaDataId", integer("Connector: 0 = source, 1..n = destination"),
                "elements", str("The complete new list of filter rules as OIE XML (<elements>...</elements>)")),
                in -> prepareElementsUpdate(in, false, false), "channel", "metaDataId", "elements");

        action("oie_update_transformer", "Replaces the transformer steps (or response transformer steps) of one connector with a new list: add, change, remove or reorder steps of any type. "
                + "Give the complete new list in OIE channel XML, in order, as in oie_get_channel: <elements> with for example "
                + "<com.mirth.connect.plugins.mapper.MapperStep version=\"...\"> (name, sequenceNumber, enabled, variable, mapping, defaultValue, replacements, scope CONNECTOR/CHANNEL/GLOBAL_CHANNEL/GLOBAL/RESPONSE), "
                + "<com.mirth.connect.plugins.messagebuilder.MessageBuilderStep> (messageSegment, mapping, defaultValue, replacements), "
                + "<com.mirth.connect.plugins.javascriptstep.JavaScriptStep> (script), <com.mirth.connect.plugins.xsltstep.XsltStep>, "
                + "<com.mirth.connect.plugins.destinationsetfilter.DestinationSetFilterStep> or <com.mirth.connect.model.IteratorStep>. "
                + "Copy unchanged steps exactly. Write scripts and mappings as XML text: escape & as &amp; and < as &lt;, or wrap them in <![CDATA[...]]>. <elements/> removes all steps. Data types and templates stay as they are. Saves the channel but does not deploy it. Requires approval.", props(
                "channel", str("Channel ID (UUID) or channel name"),
                "metaDataId", integer("Connector: 0 = source, 1..n = destination"),
                "response", bool("true = the response transformer of a destination (default false)"),
                "elements", str("The complete new list of transformer steps as OIE XML (<elements>...</elements>)")),
                in -> prepareElementsUpdate(in, true, in.path("response").asBoolean(false)), "channel", "metaDataId", "elements");

        action("oie_create_channel", "Creates a new channel from complete OIE channel XML, in the format oie_get_channel returns (root <channel version=\"...\">, "
                + "sourceConnector, destinationConnectors, filters, transformers, data types, scripts). To clone a channel, fetch it with oie_get_channel, change it and give a new name. "
                + "The channel gets a new ID and is created disabled and undeployed. Passwords and other credentials are left empty for the user to fill in. Write scripts as XML text: escape & as &amp; and < as &lt;, or use <![CDATA[...]]>. Requires approval.", props(
                "channel", str("The complete channel XML")), this::prepareCreateChannel, "channel");
    }

    private PreparedAction prepareElementsUpdate(JsonNode in, boolean transformer, boolean response) throws Exception {
        Channel current = channel(in);
        int metaDataId = in.path("metaDataId").asInt(-1);
        String elementsXml = in.path("elements").asText("");

        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        Channel copy = serializer.deserialize(serializer.serialize(current), Channel.class);
        Connector connector = connectors(copy).stream().filter(c -> c.getMetaDataId() == metaDataId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Connector with metaDataId " + metaDataId + " does not exist in '" + current.getName() + "'."));
        if (response && metaDataId == 0) {
            throw new IllegalArgumentException("The source connector has no response transformer; use a destination (metaDataId 1..n).");
        }

        List<FilterTransformerElement> parsed;
        try {
            parsed = serializer.deserializeList(ChannelEditor.elementList(elementsXml, serializer.getNormalizedVersion()), FilterTransformerElement.class);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("OIE cannot read the elements: " + e.getMessage() + ". Use the XML format of oie_get_channel, with the full class name as element name.");
        }
        for (FilterTransformerElement element : parsed) {
            if (element == null) {
                throw new IllegalArgumentException("OIE cannot read one of the elements. Use the XML format of oie_get_channel, with the full class name as element name.");
            }
            boolean fits = transformer ? element instanceof Step : element instanceof Rule;
            if (!fits) {
                throw new IllegalArgumentException("'" + element.getName() + "' (" + element.getClass().getSimpleName() + ") is a " + (transformer ? "filter rule; a transformer takes steps" : "transformer step; a filter takes rules") + ".");
            }
        }
        for (int i = 0; i < parsed.size(); i++) {
            parsed.get(i).setSequenceNumber(String.valueOf(i));
        }

        String what;
        List<? extends FilterTransformerElement> old;
        Runnable apply;
        if (!transformer) {
            what = "filter";
            old = connector.getFilter().getElements();
            List<Rule> rules = new ArrayList<>();
            parsed.forEach(e -> rules.add((Rule) e));
            apply = () -> connector.getFilter().setElements(rules);
        } else {
            Transformer target = response ? connector.getResponseTransformer() : connector.getTransformer();
            if (target == null) {
                throw new IllegalArgumentException("Connector '" + connector.getName() + "' has no " + (response ? "response transformer" : "transformer") + ".");
            }
            what = response ? "response transformer" : "transformer";
            old = target.getElements();
            List<Step> steps = new ArrayList<>();
            parsed.forEach(e -> steps.add((Step) e));
            apply = () -> target.setElements(steps);
        }

        // What each new or changed element does, as the JavaScript OIE generates from it.
        Set<String> oldXml = new HashSet<>();
        for (FilterTransformerElement e : old) {
            oldXml.add(withoutSequence(serializer.serialize(e)));
        }
        StringBuilder changed = new StringBuilder();
        Map<String, String> changedScripts = new LinkedHashMap<>();
        boolean suspicious = false;
        for (int i = 0; i < parsed.size(); i++) {
            String xml = serializer.serialize(parsed.get(i));
            if (!oldXml.contains(withoutSequence(xml))) {
                changedScripts.put((transformer ? (response ? "response transformer step " : "transformer step ") : "filter rule ") + i + " '" + parsed.get(i).getName() + "'", generatedScript(parsed.get(i)));
                changed.append("--- ").append(i).append(". [").append(parsed.get(i).getType()).append("] '").append(parsed.get(i).getName()).append("'\n")
                        .append(ChannelEditor.script(parsed.get(i))).append("\n\n");
                suspicious |= xml.contains(Masker.MASK);
            }
        }

        String location = what + " of connector " + metaDataId + " '" + connector.getName() + "'";
        String head = "Channel '" + current.getName() + "', " + location + ".\n"
                + (suspicious ? "WARNING: a new or changed element contains '" + Masker.MASK + "'. The AI Assistant saw the channel masked; check that no real value is lost.\n" : "")
                + "The channel is saved (new revision) but not deployed. If you have this channel open in the editor, reload it afterwards.\n\n";
        String kind = transformer ? (response ? "response transformer step " : "transformer step ") : "filter rule ";
        String changes = head + "New " + what + ":\n" + ChannelEditor.describe(parsed) + "\n"
                + LineDiff.elementBlocks(kind, diffElements(serializer, old), diffElements(serializer, parsed));
        String detail = head
                + "Current " + what + ":\n" + ChannelEditor.describe(old) + "\nNew " + what + ":\n" + ChannelEditor.describe(parsed)
                + (changed.length() == 0 ? "\nNo element is new or changed (only the order or the removals differ).\n" : "\nNew or changed elements, as the script OIE runs:\n\n" + changed.toString().trim());
        int revision = current.getRevision();
        PreparedAction action = new PreparedAction(transformer ? "Change transformer" : "Change filter", detail, ctx -> {
            String notSaved = save(copy, revision, ctx, apply);
            return notSaved != null ? notSaved : "The " + location + " of '" + current.getName() + "' has been saved (new revision). Deploy the channel to activate the change.";
        });
        changedScripts.forEach((label, script) -> action.withScript(label + " of connector " + metaDataId, script));
        return action.withChanges(changes);
    }

    private static List<LineDiff.Element> diffElements(ObjectXMLSerializer serializer, List<? extends FilterTransformerElement> elements) {
        List<LineDiff.Element> out = new ArrayList<>();
        if (elements != null) {
            for (FilterTransformerElement e : elements) {
                out.add(new LineDiff.Element(e.getName(), withoutSequence(serializer.serialize(e)), ChannelEditor.script(e)));
            }
        }
        return out;
    }

    /** The JavaScript OIE generates from an element, or null when it cannot (e.g. an external script file). */
    private static String generatedScript(FilterTransformerElement element) {
        try {
            return element.getScript(false);
        } catch (Exception e) {
            return null;
        }
    }

    /** Every script of a new channel: channel scripts, JavaScript connectors, filters and transformers. */
    private static void addChannelScripts(PreparedAction action, Channel channel) {
        action.withScript("deploy script", channel.getDeployScript())
                .withScript("undeploy script", channel.getUndeployScript())
                .withScript("preprocessor", channel.getPreprocessingScript())
                .withScript("postprocessor", channel.getPostprocessingScript());
        for (Connector c : connectors(channel)) {
            String of = " of connector " + c.getMetaDataId() + " '" + c.getName() + "'";
            if (c.getProperties() != null && c.getProperties().getClass().getSimpleName().startsWith("JavaScript")) {
                try {
                    action.withScript("JavaScript" + of, (String) c.getProperties().getClass().getMethod("getScript").invoke(c.getProperties()));
                } catch (ReflectiveOperationException | ClassCastException e) {
                    // No script property: nothing to check.
                }
            }
            addElements(action, "filter rule ", c.getFilter() == null ? null : c.getFilter().getElements(), of);
            addElements(action, "transformer step ", c.getTransformer() == null ? null : c.getTransformer().getElements(), of);
            addElements(action, "response transformer step ", c.getResponseTransformer() == null ? null : c.getResponseTransformer().getElements(), of);
        }
    }

    private static void addElements(PreparedAction action, String kind, List<? extends FilterTransformerElement> elements, String of) {
        if (elements == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            action.withScript(kind + i + " '" + elements.get(i).getName() + "'" + of, generatedScript(elements.get(i)));
        }
    }

    private static String withoutSequence(String xml) {
        return xml.replaceAll("<sequenceNumber>[^<]*</sequenceNumber>", "");
    }

    private PreparedAction prepareCreateChannel(JsonNode in) throws Exception {
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        List<String> cleared = new ArrayList<>();
        String xml = ChannelEditor.clearCredentials(ChannelEditor.channel(text(in, "channel"), serializer.getNormalizedVersion()), cleared);
        Channel channel;
        try {
            channel = serializer.deserialize(xml, Channel.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("OIE cannot read the channel: " + e.getMessage() + ". Use the XML format of oie_get_channel.");
        }
        if (channel == null || channel.getSourceConnector() == null) {
            throw new IllegalArgumentException("The channel has no source connector. Use the XML format of oie_get_channel.");
        }
        String name = channel.getName() == null ? "" : channel.getName().trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("The channel needs a name.");
        }
        for (Channel existing : channelController.getChannels(null)) {
            if (existing.getName().equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("A channel named '" + existing.getName() + "' already exists; choose another name.");
            }
        }
        channel.setName(name);
        channel.setId(java.util.UUID.randomUUID().toString());
        channel.setRevision(0);
        channel.clearExportData();
        // Without explicit metadata OIE would store its default: enabled.
        ChannelMetadata metadata = new ChannelMetadata();
        metadata.setEnabled(false);
        metadata.setLastModified(Calendar.getInstance());
        channel.getExportData().setMetadata(metadata);

        StringBuilder detail = new StringBuilder("Create channel '").append(name).append("' (new ID ").append(channel.getId()).append("), disabled and not deployed.\n");
        if (!cleared.isEmpty()) {
            detail.append("Left empty for you to fill in: ").append(String.join(", ", cleared)).append(".\n");
        }
        if (xml.contains(Masker.MASK)) {
            detail.append("WARNING: the channel contains '").append(Masker.MASK).append("'. The AI Assistant saw the channel it copied masked; check the settings that show it.\n");
        }
        for (Connector c : connectors(channel)) {
            detail.append("\n").append(c.getMetaDataId() == 0 ? "Source" : "Destination " + c.getMetaDataId()).append(": '").append(c.getName()).append("', ").append(c.getTransportName());
            if (c.getTransformer() != null) {
                detail.append(", data types ").append(c.getTransformer().getInboundDataType()).append(" -> ").append(c.getTransformer().getOutboundDataType());
            }
            detail.append("\n  Filter:\n").append(indent(ChannelEditor.describe(c.getFilter() == null ? null : c.getFilter().getElements())))
                    .append("  Transformer:\n").append(indent(ChannelEditor.describe(c.getTransformer() == null ? null : c.getTransformer().getElements())));
        }
        PreparedAction action = new PreparedAction("Create channel", detail.toString().trim(), ctx -> {
            for (Channel existing : channelController.getChannels(null)) {
                if (existing.getName().equalsIgnoreCase(name)) {
                    return "Not created: a channel named '" + existing.getName() + "' was created in the meantime.";
                }
            }
            if (!channelController.updateChannel(channel, ctx, false, Calendar.getInstance())) {
                return "Not created: OIE did not accept the channel.";
            }
            return "Channel '" + name + "' has been created (ID " + channel.getId() + "), disabled and not deployed."
                    + (cleared.isEmpty() ? "" : " The user must fill in: " + String.join(", ", cleared) + ".")
                    + " Enable and deploy it in the Administrator when it is ready.";
        });
        addChannelScripts(action, channel);
        return action;
    }

    private static String indent(String lines) {
        return lines.replaceAll("(?m)^", "  ");
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
        String head = "Channel '" + current.getName() + "', " + location + ".\n"
                + (suspicious ? "WARNING: the new script contains '" + Masker.MASK + "'. The AI Assistant saw the script masked; check that no real value (e.g. a number or HL7 sample) is lost.\n" : "")
                + "The channel is saved (new revision) but not deployed. If you have this channel open in the editor, reload it afterwards.\n\n";
        String detail = head + "--- current script\n" + (oldScript == null ? "" : oldScript) + "\n\n+++ new script\n" + script;
        int revision = current.getRevision();
        return new PreparedAction("Change script", detail, ctx -> {
            String notSaved = save(copy, revision, ctx, () -> apply.apply(copy));
            return notSaved != null ? notSaved : "Script saved in channel '" + current.getName() + "' (new revision). Deploy the channel to activate the change.";
        }).withScript(location, script).withChanges(head + LineDiff.block(location, oldScript, script));
    }

    private PreparedAction prepareDescriptionUpdate(JsonNode in) throws Exception {
        Channel current = channel(in);
        String description = text(in, "description").trim();
        if (description.isEmpty()) {
            throw new IllegalArgumentException("Parameter 'description' is empty.");
        }
        String old = current.getDescription() == null ? "" : current.getDescription().trim();
        if (description.equals(old)) {
            throw new IllegalArgumentException("The new description is the same as the current one.");
        }
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        Channel copy = serializer.deserialize(serializer.serialize(current), Channel.class);
        int revision = current.getRevision();
        return new PreparedAction("Update channel description", ChannelEditor.describeDescriptionChange(current.getName(), old, description), ctx -> {
            String notSaved = save(copy, revision, ctx, () -> copy.setDescription(description));
            return notSaved != null ? notSaved : "Description saved in channel '" + current.getName() + "' (new revision). It shows right away; no deploy is needed.";
        });
    }

    /**
     * Saves a changed copy of an existing channel if it is still at the revision Claude read.
     * The check and the save hold the channel controller's lock (its methods synchronize on it),
     * so nobody can save in between; OIE's own edit-date check is then not needed.
     * The stored metadata (enabled, pruning settings) and the channel's tags travel along, as the
     * Administrator sends them; without them OIE would reset them to defaults.
     *
     * @return null when saved, else why not
     */
    private String save(Channel copy, int revision, ServerEventContext ctx, Runnable apply) throws Exception {
        synchronized (channelController) {
            Channel latest = channelController.getChannelById(copy.getId());
            if (latest == null || latest.getRevision() != revision) {
                return "Not saved: the channel has changed in the meantime (revision " + (latest == null ? "?" : latest.getRevision()) + " instead of " + revision + "). Fetch the channel again.";
            }
            apply.run();
            keepMetadataAndTags(copy);
            if (!channelController.updateChannel(copy, ctx, true, Calendar.getInstance())) {
                return "Not saved: OIE did not accept the change.";
            }
            return null;
        }
    }

    private void keepMetadataAndTags(Channel channel) {
        ChannelMetadata stored = configurationController.getChannelMetadata().get(channel.getId());
        ObjectXMLSerializer serializer = ObjectXMLSerializer.getInstance();
        ChannelMetadata metadata = stored == null ? new ChannelMetadata() : serializer.deserialize(serializer.serialize(stored), ChannelMetadata.class);
        metadata.setLastModified(Calendar.getInstance());
        channel.getExportData().setMetadata(metadata);
        List<ChannelTag> tags = new ArrayList<>();
        for (ChannelTag tag : configurationController.getChannelTags()) {
            if (tag.getChannelIds() != null && tag.getChannelIds().contains(channel.getId())) {
                tags.add(tag);
            }
        }
        channel.getExportData().setChannelTags(tags);
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
