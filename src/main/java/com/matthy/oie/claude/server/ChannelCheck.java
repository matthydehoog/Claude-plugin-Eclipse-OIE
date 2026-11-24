/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mirth.connect.donkey.model.channel.ConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorProperties;
import com.mirth.connect.donkey.model.channel.DestinationConnectorPropertiesInterface;
import com.mirth.connect.model.Channel;
import com.mirth.connect.model.Connector;
import com.mirth.connect.model.FilterTransformerElement;

/**
 * oie_check_channel: known weak spots in a channel. Free of engine calls, so every check is tested
 * on a sample channel. Credentials are reported by location only: no finding ever contains a
 * value, a line of code or a setting's content.
 */
final class ChannelCheck {

    /** Kinds, most important first; findings are listed in this order. */
    static final String CREDENTIAL = "credential", CODE_TEMPLATE = "code template", NO_QUEUE = "no queue", STORAGE = "storage", LOGGING = "logging", DISABLED = "disabled";
    private static final List<String> ORDER = List.of(CREDENTIAL, CODE_TEMPLATE, NO_QUEUE, STORAGE, LOGGING, DISABLED);

    /** More would not help the model; it can check one channel at a time instead. */
    static final int MAX_FINDINGS = 400;

    /** Destinations that talk to another system, which can be down. */
    private static final Set<String> REMOTE_TRANSPORTS = Set.of("TCP Sender", "HTTP Sender", "Database Writer", "Web Service Sender", "SMTP Sender", "JMS Sender", "DICOM Sender");

    private static final String SECRET_NAME = "(?:password|passwd|pwd|passphrase|secret|token|api_?key|credential)";
    /** password = 'literal', apiKey: "literal", 'token': 'literal' */
    private static final Pattern SCRIPT_ASSIGNMENT = Pattern.compile("(?i)\\b[\\w$]*" + SECRET_NAME + "[\\w$]*['\"]?\\s*[:=]\\s*(['\"])(?!\\$\\{)[^'\"\\s]{3,}\\1");
    /** "...;password=literal" in a connection string. */
    private static final Pattern STRING_PARAM = Pattern.compile("(?i)['\"][^'\"]*\\b(?:password|passwd|pwd)\\s*=\\s*(?!\\$\\{)[^;&\\s'\"]+");
    /** scheme://user:password@host */
    private static final Pattern URL_PASSWORD = Pattern.compile("://[^/:@\\s'\"]+:(?!\\$\\{)[^@/\\s'\"]+@");
    /** 'Bearer eyJ...' or 'Basic dXNlcjpw...' */
    private static final Pattern AUTH_LITERAL = Pattern.compile("(?i)(?:^|['\">\\s])(?:Basic|Bearer)\\s+[A-Za-z0-9+/=._~-]{8,}");
    /** DatabaseConnectionFactory.createDatabaseConnection(driver, url, user, 'password') */
    private static final Pattern DB_CONNECTION = Pattern.compile("createDatabaseConnection\\s*\\((?:[^,()]*,){3}\\s*(['\"])[^'\"]+\\1\\s*\\)");
    private static final Pattern SETTING_NAME = Pattern.compile("(?i).*" + SECRET_NAME + ".*");
    private static final Pattern PASSWORD_NAME = Pattern.compile("(?i).*(?:password|passwd|pwd|passphrase).*");
    private static final Pattern VALUE_PARAM = Pattern.compile("(?i)\\b(?:password|passwd|pwd)\\s*=\\s*[^;&\\s]+");
    private static final Pattern LEAF = Pattern.compile("<([A-Za-z_][\\w.\\-]*)(?:\\s[^>]*)?>([^<]*)</\\1>");
    private static final Pattern LOGGER = Pattern.compile("\\blogger\\s*\\.\\s*(info|debug|trace)\\s*\\(");
    private static final Pattern FUNCTION = Pattern.compile("\\bfunction\\s+([A-Za-z_$][\\w$]*)\\s*\\(");

    /** One weak spot: its kind, where it is and what it means. Never a value or code. */
    static final class Finding {
        final String kind;
        final String where;
        final String text;

        Finding(String kind, String where, String text) {
            this.kind = kind;
            this.where = where;
            this.text = text;
        }

        @Override
        public String toString() {
            return "[" + kind + "] " + where + ": " + text;
        }
    }

    /** A script of the channel: where it is, whether it runs per message, and whether line numbers mean something. */
    private static final class Script {
        final String where;
        final String text;
        final boolean perMessage;
        final boolean lines;

        Script(String where, String text, boolean perMessage, boolean lines) {
            this.where = where;
            this.text = text == null ? "" : text;
            this.perMessage = perMessage;
            this.lines = lines;
        }
    }

    private ChannelCheck() {
    }

    /**
     * @param pruneMetaDataDays  the channel's pruning, null when off (or unknown)
     * @param pruneContentDays   the same for content
     * @param unlinkedFunctions  function name to library name, for the code template libraries not
     *                           linked to this channel (without names a linked library also defines)
     * @param serializer         connector properties as XML
     */
    static List<Finding> check(Channel channel, Integer pruneMetaDataDays, Integer pruneContentDays, Map<String, String> unlinkedFunctions, Function<Object, String> serializer) {
        List<Finding> findings = new ArrayList<>();
        List<Script> scripts = scripts(channel);

        // Disabled destinations, rules and steps.
        for (Connector c : connectors(channel)) {
            String where = connectorName(c);
            if (c.getMetaDataId() > 0 && !c.isEnabled()) {
                findings.add(new Finding(DISABLED, where, "the destination is disabled; messages are not sent to it"));
            }
            disabled(findings, where + ", filter rule ", c.getFilter() == null ? null : c.getFilter().getElements());
            disabled(findings, where + ", transformer step ", c.getTransformer() == null ? null : c.getTransformer().getElements());
            if (c.getMetaDataId() > 0) {
                disabled(findings, where + ", response transformer step ", c.getResponseTransformer() == null ? null : c.getResponseTransformer().getElements());
            }
        }

        // Credentials in scripts and settings, by location only.
        for (Script s : scripts) {
            String[] lines = s.text.split("\r?\n", -1);
            List<Integer> found = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (scriptCredential(lines[i])) {
                    found.add(i + 1);
                }
            }
            if (!found.isEmpty()) {
                findings.add(new Finding(CREDENTIAL, s.where + lineList(s, found), "a password, token or key seems to be typed into the script; keep it in the Configuration Map ($cfg('key')) instead"));
            }
        }
        for (Connector c : connectors(channel)) {
            ConnectorProperties properties = c.getProperties();
            if (properties == null) {
                continue;
            }
            String xml;
            try {
                xml = Masker.unescapeXml(serializer.apply(properties));
            } catch (RuntimeException e) {
                continue;
            }
            Set<String> settings = new LinkedHashSet<>();
            Matcher leaf = LEAF.matcher(xml == null ? "" : xml);
            while (leaf.find()) {
                if (settingCredential(leaf.group(1), leaf.group(2))) {
                    settings.add(leaf.group(1).equals("string") ? "headers or parameters" : leaf.group(1));
                }
            }
            for (String setting : settings) {
                findings.add(new Finding(CREDENTIAL, connectorName(c) + ", setting " + setting, "a credential is typed into the setting; use a Configuration Map key such as ${dbPassword} instead"));
            }
        }

        // Code template functions from libraries the channel is not linked to.
        Set<String> ownFunctions = new LinkedHashSet<>();
        for (Script s : scripts) {
            Matcher m = FUNCTION.matcher(s.text);
            while (m.find()) {
                ownFunctions.add(m.group(1));
            }
        }
        for (Map.Entry<String, String> f : unlinkedFunctions.entrySet()) {
            if (ownFunctions.contains(f.getKey())) {
                continue;
            }
            Pattern call = Pattern.compile("(?<![\\w$.])" + Pattern.quote(f.getKey()) + "\\s*\\(");
            for (Script s : scripts) {
                String[] lines = s.text.split("\r?\n", -1);
                List<Integer> found = new ArrayList<>();
                for (int i = 0; i < lines.length; i++) {
                    if (call.matcher(lines[i]).find()) {
                        found.add(i + 1);
                    }
                }
                if (!found.isEmpty()) {
                    findings.add(new Finding(CODE_TEMPLATE, s.where + lineList(s, found), "calls " + f.getKey() + "() from code template library '" + f.getValue()
                            + "', which is not linked to this channel, so the call fails with '" + f.getKey() + " is not defined'; link the library to the channel"));
                }
            }
        }

        // Remote destinations without a queue.
        for (Connector c : connectors(channel)) {
            if (c.getMetaDataId() == 0 || !c.isEnabled() || !REMOTE_TRANSPORTS.contains(c.getTransportName()) || !(c.getProperties() instanceof DestinationConnectorPropertiesInterface)) {
                continue;
            }
            DestinationConnectorProperties d = ((DestinationConnectorPropertiesInterface) c.getProperties()).getDestinationConnectorProperties();
            if (d != null && !d.isQueueEnabled()) {
                findings.add(new Finding(NO_QUEUE, connectorName(c), "the queue is off" + (d.getRetryCount() > 0 ? " (" + d.getRetryCount() + " retries)" : " and there are no retries")
                        + ": when the other system is down, messages end in ERROR instead of waiting; consider queueing (On Failure or Always)"));
            }
        }

        // Storage and pruning.
        Enum<?> storage = channel.getProperties() == null ? null : channel.getProperties().getMessageStorageMode();
        String mode = storage == null ? "" : storage.name();
        if (mode.equals("DEVELOPMENT")) {
            findings.add(new Finding(STORAGE, "channel", "message storage is Development: every content type and map of every message is stored; use Production or lower outside development"));
        }
        if (!mode.equals("DISABLED") && pruneMetaDataDays == null && pruneContentDays == null) {
            findings.add(new Finding(STORAGE, "channel", "message pruning is off: messages are kept forever and the database keeps growing; set Prune metadata older than a number of days"));
        }

        // Logging that runs for every message.
        for (Script s : scripts) {
            if (!s.perMessage) {
                continue;
            }
            String[] lines = s.text.split("\r?\n", -1);
            List<Integer> found = new ArrayList<>();
            Set<String> levels = new LinkedHashSet<>();
            for (int i = 0; i < lines.length; i++) {
                Matcher m = LOGGER.matcher(lines[i]);
                if (m.find()) {
                    found.add(i + 1);
                    levels.add("logger." + m.group(1));
                }
            }
            if (!found.isEmpty()) {
                findings.add(new Finding(LOGGING, s.where + lineList(s, found), String.join(", ", levels) + " runs for every message and fills the server log; remove it or use it only while testing"));
            }
        }

        findings.sort(Comparator.comparingInt(f -> ORDER.indexOf(f.kind)));
        return findings;
    }

    /** The result of checking channels, as text for the model. */
    static String report(List<Map.Entry<String, List<Finding>>> results) {
        int total = 0;
        List<String> clean = new ArrayList<>();
        for (Map.Entry<String, List<Finding>> r : results) {
            total += r.getValue().size();
            if (r.getValue().isEmpty()) {
                clean.add(r.getKey());
            }
        }
        int withFindings = results.size() - clean.size();
        StringBuilder out = new StringBuilder("Checked ").append(results.size()).append(results.size() == 1 ? " channel" : " channels").append(": ")
                .append(total).append(total == 1 ? " finding" : " findings");
        if (results.size() > 1) {
            out.append(" in ").append(withFindings).append(withFindings == 1 ? " channel" : " channels");
        }
        out.append(". Credentials are named by location only.\n");
        int shown = 0;
        boolean clipped = false;
        for (Map.Entry<String, List<Finding>> r : results) {
            if (r.getValue().isEmpty()) {
                continue;
            }
            out.append('\n').append(r.getKey()).append('\n');
            for (Finding f : r.getValue()) {
                if (shown >= MAX_FINDINGS) {
                    clipped = true;
                    break;
                }
                out.append("- ").append(f).append('\n');
                shown++;
            }
            if (clipped) {
                break;
            }
        }
        if (clipped) {
            out.append("\n(first ").append(MAX_FINDINGS).append(" findings shown; check one channel at a time for the rest)\n");
        }
        if (!clean.isEmpty() && results.size() > 1) {
            out.append("\nNo findings: ").append(String.join(", ", clean)).append('\n');
        }
        return out.toString().trim();
    }

    static boolean scriptCredential(String line) {
        if (line.contains("${")) {
            line = line.replaceAll("\\$\\{[^}]*\\}", "");
        }
        return SCRIPT_ASSIGNMENT.matcher(line).find() || STRING_PARAM.matcher(line).find() || URL_PASSWORD.matcher(line).find()
                || AUTH_LITERAL.matcher(line).find() || DB_CONNECTION.matcher(line).find();
    }

    static boolean settingCredential(String name, String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.contains("${")) {
            return false;
        }
        if (SETTING_NAME.matcher(name).matches() && !v.equals("true") && !v.equals("false")) {
            // A password setting is a credential whatever it holds; for token, secret and key settings
            // a number is more likely a size or timeout.
            return PASSWORD_NAME.matcher(name).matches() || !v.matches("\\d+");
        }
        return VALUE_PARAM.matcher(v).find() || URL_PASSWORD.matcher(v).find() || AUTH_LITERAL.matcher(" " + v).find();
    }

    private static void disabled(List<Finding> findings, String prefix, List<? extends FilterTransformerElement> elements) {
        if (elements == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            if (!elements.get(i).isEnabled()) {
                findings.add(new Finding(DISABLED, prefix + i + " '" + elements.get(i).getName() + "'", "is disabled; check that this is intended"));
            }
        }
    }

    private static List<Script> scripts(Channel channel) {
        List<Script> scripts = new ArrayList<>();
        scripts.add(new Script("deploy script", channel.getDeployScript(), false, true));
        scripts.add(new Script("undeploy script", channel.getUndeployScript(), false, true));
        scripts.add(new Script("preprocessor", channel.getPreprocessingScript(), true, true));
        scripts.add(new Script("postprocessor", channel.getPostprocessingScript(), true, true));
        for (Connector c : connectors(channel)) {
            String where = connectorName(c);
            String script = javaScriptProperty(c.getProperties());
            if (script != null) {
                // A JavaScript Writer runs per message; a JavaScript Reader per poll.
                scripts.add(new Script(where + ", script", script, c.getMetaDataId() > 0, true));
            }
            elementScripts(scripts, where + ", filter rule ", c.getFilter() == null ? null : c.getFilter().getElements());
            elementScripts(scripts, where + ", transformer step ", c.getTransformer() == null ? null : c.getTransformer().getElements());
            if (c.getMetaDataId() > 0) {
                elementScripts(scripts, where + ", response transformer step ", c.getResponseTransformer() == null ? null : c.getResponseTransformer().getElements());
            }
        }
        return scripts;
    }

    private static void elementScripts(List<Script> scripts, String prefix, List<? extends FilterTransformerElement> elements) {
        if (elements == null) {
            return;
        }
        for (int i = 0; i < elements.size(); i++) {
            FilterTransformerElement e = elements.get(i);
            if (!e.isEnabled()) {
                continue; // a disabled element does not run; it is reported as disabled
            }
            boolean javaScript = "JavaScript".equals(e.getType()) || e.getClass().getSimpleName().startsWith("JavaScript");
            scripts.add(new Script(prefix + i + " '" + e.getName() + "'" + (javaScript ? "" : " [" + e.getType() + "]"), ChannelEditor.script(e), true, javaScript));
        }
    }

    private static String javaScriptProperty(Object properties) {
        if (properties == null || !properties.getClass().getSimpleName().startsWith("JavaScript")) {
            return null;
        }
        try {
            Object script = properties.getClass().getMethod("getScript").invoke(properties);
            return script instanceof String ? (String) script : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String lineList(Script s, List<Integer> lines) {
        if (!s.lines) {
            return "";
        }
        if (lines.size() == 1) {
            return ", line " + lines.get(0);
        }
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < Math.min(lines.size(), 10); i++) {
            shown.add(String.valueOf(lines.get(i)));
        }
        return ", lines " + String.join(", ", shown) + (lines.size() > 10 ? " and " + (lines.size() - 10) + " more" : "");
    }

    private static String connectorName(Connector c) {
        return (c.getMetaDataId() == 0 ? "source" : "destination " + c.getMetaDataId()) + " '" + c.getName() + "' (" + c.getTransportName() + ")";
    }

    private static List<Connector> connectors(Channel channel) {
        List<Connector> out = new ArrayList<>();
        if (channel.getSourceConnector() != null) {
            out.add(channel.getSourceConnector());
        }
        if (channel.getDestinationConnectors() != null) {
            out.addAll(channel.getDestinationConnectors());
        }
        return out;
    }

    /** The functions a code template defines. */
    static Set<String> functions(String code) {
        Set<String> out = new LinkedHashSet<>();
        if (code != null) {
            Matcher m = FUNCTION.matcher(code);
            while (m.find()) {
                out.add(m.group(1));
            }
        }
        return out;
    }
}
