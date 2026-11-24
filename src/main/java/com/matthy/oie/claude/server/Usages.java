/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * oie_find_usages: where a Configuration Map key, a map variable, a code template function or a
 * channel is used. Works on plain text sources, so it is tested without OIE. The result holds only
 * locations and the form of each use (for example $cfg or Channel Writer), never code or values.
 */
final class Usages {

    static final String CONFIG = "config", MAP = "map", FUNCTION = "function", CHANNEL = "channel";
    static final String[] KINDS = { CONFIG, MAP, FUNCTION, CHANNEL };

    /** More would not help the model; it can narrow the question instead. */
    static final int MAX_LOCATIONS = 300;

    private static final String Q = "(['\"])";
    private static final Pattern OPEN_TAG = Pattern.compile("<([A-Za-z_][\\w.\\-]*)(?:\\s[^>]*)?>");
    private static final Pattern CLOSE_TAG = Pattern.compile("</([A-Za-z_][\\w.\\-]*)>");

    /** One place to search: a script, or the serialized settings of a connector. */
    static final class Source {
        final String group;
        final String where;
        final String text;
        final boolean properties;
        final boolean lines;

        /**
         * @param group      heading the location is listed under (e.g. "Channel 'ADT in' (id)")
         * @param where      the place within it (e.g. "destination 1 'To DB', transformer step 2 'Map PID'")
         * @param text       script or (unescaped) properties XML
         * @param properties text is connector properties XML: report the setting name instead of a line
         * @param lines      report line numbers (not for scripts OIE generates from Mapper and other elements)
         */
        Source(String group, String where, String text, boolean properties, boolean lines) {
            this.group = group;
            this.where = where;
            this.text = text == null ? "" : text;
            this.properties = properties;
            this.lines = lines;
        }
    }

    /** A form of use: its label in the result and its pattern. */
    private static final class Form {
        final String label;
        final Pattern pattern;

        Form(String label, String regex) {
            this.label = label;
            this.pattern = Pattern.compile(regex);
        }
    }

    private final String kind;
    private final String target;
    private final List<Form> forms = new ArrayList<>();

    /**
     * @param kind      one of {@link #KINDS}
     * @param name      the key, variable or function name, or the channel's name
     * @param channelId for {@link #CHANNEL}: the channel's ID (null when no such channel exists)
     */
    Usages(String kind, String name, String channelId) {
        this.kind = kind;
        String n = Pattern.quote(name);
        switch (kind) {
            case CONFIG:
                this.target = "Configuration Map key '" + name + "'";
                forms.add(new Form("$cfg", "\\$cfg\\s*\\(\\s*" + Q + n + "\\1\\s*\\)"));
                forms.add(new Form("configurationMap.get", "configurationMap\\s*\\.\\s*get\\s*\\(\\s*" + Q + n + "\\1"));
                forms.add(new Form("$() lookup", "(?<![\\w$])\\$\\s*\\(\\s*" + Q + n + "\\1\\s*\\)"));
                forms.add(new Form("${" + name + "}", "\\$\\{" + n + "\\}"));
                break;
            case MAP:
                this.target = "map variable '" + name + "'";
                for (String map : new String[] { "globalMap", "globalChannelMap", "channelMap", "connectorMap", "responseMap", "sourceMap", "configurationMap" }) {
                    forms.add(new Form(map, "(?<![\\w$])" + map + "\\s*\\.\\s*(?:get|put|containsKey|remove)\\s*\\(\\s*" + Q + n + "\\1"));
                }
                String[][] shortcuts = { { "$g", "globalMap" }, { "$gc", "globalChannelMap" }, { "$c", "channelMap" }, { "$co", "connectorMap" }, { "$r", "responseMap" }, { "$s", "sourceMap" }, { "$cfg", "configurationMap" } };
                for (String[] s : shortcuts) {
                    forms.add(new Form(s[0] + " (" + s[1] + ")", "(?<![\\w$])" + Pattern.quote(s[0]) + "\\s*\\(\\s*" + Q + n + "\\1"));
                }
                forms.add(new Form("$() lookup", "(?<![\\w$])\\$\\s*\\(\\s*" + Q + n + "\\1\\s*\\)"));
                forms.add(new Form("${" + name + "}", "\\$\\{" + n + "\\}"));
                break;
            case FUNCTION:
                this.target = "function '" + name + "'";
                forms.add(new Form("definition", "\\bfunction\\s+" + n + "\\s*\\("));
                forms.add(new Form("call", "(?<![\\w$.])" + n + "\\s*\\("));
                break;
            case CHANNEL:
                this.target = "channel '" + name + "'" + (channelId == null ? "" : " (" + channelId + ")");
                forms.add(new Form("router.routeMessage", "routeMessage\\s*\\(\\s*" + Q + n + "\\1"));
                if (channelId != null) {
                    String id = Pattern.quote(channelId);
                    forms.add(new Form("router.routeMessageByChannelId", "routeMessageByChannelId\\s*\\(\\s*" + Q + id + "\\1"));
                    forms.add(new Form("Channel Writer", "<channelId>\\s*" + id + "\\s*</channelId>"));
                    forms.add(new Form("channel ID", id));
                }
                break;
            default:
                throw new IllegalArgumentException("Unknown kind '" + kind + "'. Choose from: " + String.join(", ", KINDS));
        }
    }

    /** The locations as text, grouped by channel, code template or global script; no code or values. */
    String find(List<Source> sources) {
        Map<String, List<String>> byGroup = new LinkedHashMap<>();
        int count = 0;
        boolean clipped = false;
        for (Source source : sources) {
            for (String location : locations(source)) {
                if (count >= MAX_LOCATIONS) {
                    clipped = true;
                    break;
                }
                byGroup.computeIfAbsent(source.group, g -> new ArrayList<>()).add(location);
                count++;
            }
        }
        if (count == 0) {
            return "No usages of " + target + " found in the channels, code templates and global scripts" + (CONFIG.equals(kind) || MAP.equals(kind) ? " (names are case-sensitive)." : ".");
        }
        StringBuilder out = new StringBuilder("Usages of ").append(target).append(": ").append(count).append(count == 1 ? " location" : " locations")
                .append(" in ").append(byGroup.size()).append(byGroup.size() == 1 ? " place" : " places").append(clipped ? " (first " + MAX_LOCATIONS + " shown; narrow the question)" : "").append(".\n");
        for (Map.Entry<String, List<String>> group : byGroup.entrySet()) {
            out.append('\n').append(group.getKey()).append('\n');
            for (String location : group.getValue()) {
                out.append("- ").append(location).append('\n');
            }
        }
        return out.toString().trim();
    }

    /** "where, line N: form" per line that uses the target (forms on one line are joined). */
    List<String> locations(Source source) {
        List<String> out = new ArrayList<>();
        String[] lines = source.text.split("\r?\n", -1);
        // For properties XML: the innermost open element, which names the setting.
        List<String> open = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Set<String> found = new LinkedHashSet<>();
            for (Form form : forms) {
                if (form.pattern.matcher(line).find()) {
                    found.add(form.label);
                }
            }
            // A definition is not also a call; a Channel Writer is not also a bare ID.
            if (found.contains("definition")) {
                found.remove("call");
            }
            if (found.contains("Channel Writer") || found.contains("router.routeMessageByChannelId")) {
                found.remove("channel ID");
            }
            String setting = null;
            if (source.properties) {
                setting = setting(line, open);
            }
            if (!found.isEmpty()) {
                String place = source.where;
                if (source.properties) {
                    place += setting == null ? "" : ", setting " + setting;
                } else if (source.lines) {
                    place += ", line " + (i + 1);
                }
                out.add(place + ": " + String.join(", ", found));
            }
        }
        return out;
    }

    /** The setting a properties line belongs to, keeping track of the elements open before it. */
    private static String setting(String line, List<String> open) {
        Matcher o = OPEN_TAG.matcher(line);
        String first = null;
        while (o.find()) {
            if (line.charAt(o.end() - 2) != '/') {
                open.add(o.group(1));
                first = first == null ? o.group(1) : first;
            }
        }
        String current = first != null ? first : open.isEmpty() ? null : open.get(open.size() - 1);
        Matcher c = CLOSE_TAG.matcher(line);
        while (c.find()) {
            int at = open.lastIndexOf(c.group(1));
            if (at >= 0) {
                open.subList(at, open.size()).clear();
            }
        }
        return current;
    }
}
