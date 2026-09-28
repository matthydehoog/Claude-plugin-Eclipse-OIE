package com.matthy.oie.claude.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Saved conversations: one JSON file per conversation, in a folder per user. A file holds the
 * readable log and the model history, both exactly as sent to Claude, so masked.
 */
class ConversationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("[0-9a-fA-F-]{36}");

    private final File root;

    ConversationStore(File root) {
        this.root = root;
    }

    /** A saved conversation as read back. */
    static final class Saved {
        final String id;
        final int userId;
        final long created;
        final long lastUsed;
        final String title;
        final List<Conversation.Entry> log = new ArrayList<>();
        final String history;

        Saved(JsonNode node) {
            id = node.path("id").asText();
            userId = node.path("userId").asInt();
            created = node.path("created").asLong();
            lastUsed = node.path("lastUsed").asLong();
            title = node.path("title").asText("");
            for (JsonNode e : node.path("log")) {
                log.add(new Conversation.Entry(e.path("type").asText(), e.path("text").asText()));
            }
            history = node.hasNonNull("history") ? node.get("history").asText() : null;
        }
    }

    void save(Conversation conversation, String history) throws IOException {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", conversation.id);
        node.put("userId", conversation.userId);
        node.put("created", conversation.created);
        node.put("lastUsed", conversation.lastUsed);
        node.put("title", conversation.title());
        ArrayNode log = node.putArray("log");
        for (Conversation.Entry e : conversation.log()) {
            log.addObject().put("type", e.type).put("text", e.text);
        }
        node.put("history", history);

        File dir = userDir(conversation.userId);
        Files.createDirectories(dir.toPath());
        File file = new File(dir, conversation.id + ".json");
        File tmp = new File(dir, conversation.id + ".json.tmp");
        Files.write(tmp.toPath(), MAPPER.writeValueAsBytes(node));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** The user's saved conversations, most recently used first, without their log and history. */
    List<Saved> list(int userId) {
        List<Saved> list = new ArrayList<>();
        File[] files = userDir(userId).listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try {
                    list.add(new Saved(MAPPER.readTree(f)));
                } catch (IOException e) {
                    // a damaged file is skipped, not fatal for the list
                }
            }
        }
        list.sort(Comparator.comparingLong((Saved s) -> s.lastUsed).reversed());
        return list;
    }

    /** @return null when the user has no saved conversation with this ID */
    Saved load(int userId, String id) throws IOException {
        if (id == null || !ID.matcher(id).matches()) {
            return null;
        }
        File file = new File(userDir(userId), id + ".json");
        if (!file.isFile()) {
            return null;
        }
        Saved saved = new Saved(MAPPER.readTree(file));
        return saved.userId == userId ? saved : null;
    }

    /** Removes saved conversations not used for more than the given number of days (all of them when 0). */
    int removeOlderThan(int days) {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(Math.max(days, 0));
        int removed = 0;
        File[] users = root.listFiles(File::isDirectory);
        if (users == null) {
            return 0;
        }
        for (File user : users) {
            File[] files = user.listFiles((d, name) -> name.endsWith(".json") || name.endsWith(".json.tmp"));
            if (files == null) {
                continue;
            }
            for (File f : files) {
                if (days <= 0 || f.lastModified() < cutoff) {
                    if (f.delete()) {
                        removed++;
                    }
                }
            }
        }
        return removed;
    }

    private File userDir(int userId) {
        return new File(root, String.valueOf(userId));
    }

    /** Readable Markdown of a conversation log, for export. */
    static String markdown(String title, String server, long created, List<Conversation.Entry> log) {
        StringBuilder sb = new StringBuilder("# Claude Assistant conversation\n\n");
        sb.append("- **Server:** ").append(server).append('\n');
        sb.append("- **Started:** ").append(java.time.Instant.ofEpochMilli(created).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append('\n');
        sb.append("- **Subject:** ").append(title).append("\n\n");
        sb.append("> Everything below is what Claude received and answered. Patient data was masked before sending and appears as `***`.\n");
        int question = 0;
        boolean inTools = false;
        for (Conversation.Entry e : log) {
            if (!e.type.equals("tool") && inTools) {
                inTools = false;
            }
            switch (e.type) {
                case "question":
                    sb.append("\n## Question ").append(++question).append("\n\n").append(e.text.strip()).append('\n');
                    break;
                case "text":
                    sb.append("\n### Claude\n\n").append(e.text.strip()).append('\n');
                    break;
                case "tool":
                    if (!inTools) {
                        sb.append('\n');
                        inTools = true;
                    }
                    sb.append("- Tool: `").append(e.text.replace("`", "'")).append("`\n");
                    break;
                case "action":
                    sb.append("\n**Action done:** ").append(e.text.strip()).append('\n');
                    break;
                case "error":
                    sb.append("\n**Error:** ").append(e.text.strip()).append('\n');
                    break;
                default:
                    sb.append("\n_").append(e.text.strip()).append("_\n");
            }
        }
        return sb.toString();
    }
}
