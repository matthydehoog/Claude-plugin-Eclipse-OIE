/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.FilterTransformerElement;
import com.mirth.connect.model.Transformer;

/**
 * A channel described for documentation: per connector its settings, filter rules and transformer
 * steps (with the script OIE generates for each), the channel scripts, the code template libraries
 * and the Configuration Map keys it uses. Shorter and easier to read than the channel XML, and
 * secrets are hidden before the masker even sees it. Part of {@link OieTools}; free of engine
 * calls so it can be tested on its own.
 */
final class ChannelOverview {

    static final String HIDDEN = "(hidden)";

    private static final String SECRET_WORDS = "password|passwd|passphrase|secret|token|api[-_ ]?key|authorization|cookie|credential";
    /** "Authorization: Bearer ..." or "PASSWORD: ..." in OIE's formatted connector settings (not in scripts, where a colon means something else). */
    private static final Pattern SECRET_LINE = Pattern.compile("(?im)^([ \\t]*[^:\\r\\n]*(?:" + SECRET_WORDS + ")[^:\\r\\n]*:[ \\t]*)\\S.*$");
    /** "password=..." in a JDBC URL or connection string. */
    private static final Pattern SECRET_PARAM = Pattern.compile("(?i)\\b((?:password|passwd|pwd)\\s*=\\s*)[^;&\\s'\"]+");
    /** "scheme://user:password@host". */
    private static final Pattern URL_PASSWORD = Pattern.compile("(://[^/:@\\s'\"]+:)[^@/\\s'\"]+@");

    /** Comments and whitespace, to recognise the scripts OIE puts in a new channel. */
    private static final Pattern COMMENTS = Pattern.compile("(?s)/\\*.*?\\*/|//[^\\r\\n]*|\\s+");

    private ChannelOverview() {}

    /**
     * @param only           connectors to include (null = all)
     * @param enabled        whether the channel is enabled, or null if unknown
     * @param libraries      names of the code template libraries linked to the channel
     * @param configKeys     the Configuration Map keys the channel uses
     * @param channelNames   all channel names by ID, to name the targets of Channel Writers
     * @param serializer     turns connector properties into XML, for connectors without a formatted form
     */
    static String describe(Channel channel, Set<Integer> only, Boolean enabled, List<String> libraries, Collection<String> configKeys,
            Map<String, String> channelNames, Function<Object, String> serializer) {
        StringBuilder sb = new StringBuilder();
        sb.append("Channel '").append(channel.getName()).append("' (").append(channel.getId()).append(", revision ").append(channel.getRevision()).append(')');
        if (enabled != null) {
            sb.append(enabled ? ", enabled" : ", disabled");
        }
        sb.append('\n');
        if (channel.getDescription() != null && !channel.getDescription().isBlank()) {
            sb.append("Description: ").append(channel.getDescription().trim()).append('\n');
        }
        if (channel.getProperties() != null) {
            // name(), not toString(): the enums' toString needs commons-text.
            Enum<?> state = channel.getProperties().getInitialState();
            Enum<?> storage = channel.getProperties().getMessageStorageMode();
            sb.append("Initial state: ").append(state == null ? "?" : state.name())
                    .append("; message storage: ").append(storage == null ? "?" : storage.name()).append('\n');
        }
        sb.append("Code template libraries: ").append(libraries.isEmpty() ? "(none)" : String.join(", ", libraries)).append('\n');
        sb.append("Configuration Map keys used: ").append(configKeys.isEmpty() ? "(none)" : String.join(", ", configKeys)).append('\n');

        sb.append("\n== Channel scripts\n");
        script(sb, "Deploy", channel.getDeployScript());
        script(sb, "Undeploy", channel.getUndeployScript());
        script(sb, "Preprocessor", channel.getPreprocessingScript());
        script(sb, "Postprocessor", channel.getPostprocessingScript());

        List<Connector> connectors = new ArrayList<>();
        connectors.add(channel.getSourceConnector());
        connectors.addAll(channel.getDestinationConnectors());
        List<Integer> skipped = new ArrayList<>();
        for (Connector c : connectors) {
            if (only != null && !only.contains(c.getMetaDataId())) {
                skipped.add(c.getMetaDataId());
                continue;
            }
            connector(sb, c, channelNames, serializer);
        }
        if (!skipped.isEmpty()) {
            sb.append("\n(Connectors not included: ").append(skipped).append(". Ask for them with metaDataIds.)\n");
        }
        return hideSecrets(sb.toString());
    }

    private static void connector(StringBuilder sb, Connector c, Map<String, String> channelNames, Function<Object, String> serializer) {
        boolean source = c.getMetaDataId() == 0;
        sb.append("\n== ").append(source ? "Source" : "Destination " + c.getMetaDataId()).append(": '").append(c.getName()).append("' (").append(c.getTransportName()).append(')');
        if (!source) {
            sb.append(c.isEnabled() ? "" : ", DISABLED").append(c.isWaitForPrevious() ? ", waits for the previous destination" : ", runs in parallel with the previous destination");
        }
        sb.append('\n');
        Transformer t = c.getTransformer();
        if (t != null) {
            sb.append("Data types: ").append(t.getInboundDataType()).append(" -> ").append(t.getOutboundDataType()).append('\n');
        }
        ConnectorProperties properties = c.getProperties();
        if (properties instanceof DestinationConnectorPropertiesInterface) {
            DestinationConnectorProperties d = ((DestinationConnectorPropertiesInterface) properties).getDestinationConnectorProperties();
            if (d != null) {
                sb.append("Queue: ").append(d.isQueueEnabled() ? "on" : "off").append(", retries: ").append(d.getRetryCount()).append('\n');
            }
        }
        sb.append("Settings:\n").append(indent(settings(properties, serializer, channelNames)));
        elements(sb, "Filter", c.getFilter() == null ? null : c.getFilter().getElements());
        elements(sb, "Transformer", t == null ? null : t.getElements());
        if (!source && c.getResponseTransformer() != null && !c.getResponseTransformer().getElements().isEmpty()) {
            elements(sb, "Response transformer", c.getResponseTransformer().getElements());
        }
    }

    /** OIE's own readable form of the settings, or the XML (credentials emptied) when it has none. */
    static String settings(ConnectorProperties properties, Function<Object, String> serializer, Map<String, String> channelNames) {
        if (properties == null) {
            return "(none)\n";
        }
        String text = null;
        try {
            text = properties.toFormattedString();
        } catch (RuntimeException e) {
            // Some connectors cannot format partly filled settings; use the XML instead.
        }
        if (text == null || text.isBlank()) {
            // Plugin properties hold authentication settings (such as Basic auth users and passwords).
            text = ChannelEditor.clearCredentials(serializer.apply(properties), new ArrayList<>())
                    .replaceAll("(?s)<pluginProperties>.*?</pluginProperties>", "<pluginProperties>" + HIDDEN + "</pluginProperties>");
        }
        text = SECRET_LINE.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(HIDDEN));
        for (Map.Entry<String, String> e : channelNames.entrySet()) {
            text = text.replace(e.getKey(), e.getKey() + " ('" + e.getValue() + "')");
        }
        return text.trim() + "\n";
    }

    private static void elements(StringBuilder sb, String what, List<? extends FilterTransformerElement> elements) {
        sb.append(what).append(":\n").append(ChannelEditor.describe(elements));
        if (elements == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            FilterTransformerElement e = elements.get(i);
            sb.append("  --- ").append(what.toLowerCase()).append(' ').append(i).append(" '").append(e.getName()).append("' as the script OIE runs:\n")
                    .append(indent(indent(ChannelEditor.script(e) + "\n")));
        }
    }

    private static void script(StringBuilder sb, String name, String script) {
        if (isDefaultScript(script)) {
            sb.append(name).append(": (default, does nothing)\n");
        } else {
            sb.append(name).append(":\n").append(indent(script.trim() + "\n"));
        }
    }

    /** True for an empty script or one of the scripts OIE puts in a new channel. */
    static boolean isDefaultScript(String script) {
        if (script == null) {
            return true;
        }
        String code = COMMENTS.matcher(script).replaceAll("");
        return code.isEmpty() || code.equals("return;") || code.equals("returnmessage;");
    }

    /** The Configuration Map keys the text refers to: $cfg('key'), configurationMap.get('key'), $('key') or ${key}. */
    static List<String> configKeysUsed(String text, Collection<String> keys) {
        Set<String> used = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String key : keys) {
            String k = Pattern.quote(key);
            Pattern p = Pattern.compile("(?:\\$cfg|configurationMap\\.get|\\$)\\s*\\(\\s*(['\"])" + k + "\\1|\\$\\{" + k + "\\}");
            if (p.matcher(text).find()) {
                used.add(key);
            }
        }
        return new ArrayList<>(used);
    }

    /**
     * Hides password= parameters and user:password@ in URLs, anywhere (also in scripts). Settings
     * lines named like a secret (Authorization: ..., PASSWORD: ...) are hidden in {@link #settings}.
     */
    static String hideSecrets(String text) {
        String out = SECRET_PARAM.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(HIDDEN));
        return URL_PASSWORD.matcher(out).replaceAll("$1" + Matcher.quoteReplacement(HIDDEN) + "@");
    }

    private static String indent(String lines) {
        return lines.replaceAll("(?m)^(?=.)", "  ");
    }
}
