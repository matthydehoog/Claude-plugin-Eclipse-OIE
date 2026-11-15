/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The text of oie_queues_and_storage: queues per destination, stored messages per channel and the
 * database. Only counts, dates, settings and error lines; never message content. Kept apart from
 * OieTools so it can be tested without an engine.
 */
final class QueueStorageReport {

    /** Queues older than this are called out as stuck. */
    static final Duration STUCK_AFTER = Duration.ofHours(1);

    private QueueStorageReport() {
    }

    /** A source or destination connector and its queue. */
    static final class Queue {
        String channel;
        String channelId;
        String connector;
        int metaDataId;
        /** Connector state (STARTED, STOPPED, ...), or the channel's when the connector has none; UNDEPLOYED when not deployed. */
        String state;
        boolean queueEnabled;
        /** Destinations only: send first, then queue on failure. */
        boolean sendFirst;
        int retryCount;
        int retryIntervalMillis;
        int threads = 1;
        /** Messages waiting; null when unknown. */
        Long queued;
        Instant oldestQueued;
        Integer oldestSendAttempts;
        Instant lastAttempt;
        String lastError;
    }

    /** One channel's stored messages and the settings that decide how long they stay. */
    static final class Storage {
        String channel;
        String channelId;
        String state;
        /** Null when the channel was never deployed (no message tables yet) or counting failed. */
        Long messages;
        Instant oldest;
        /** Estimate from the database; null when OIE cannot tell. */
        Long bytes;
        String storageMode;
        boolean removeContentOnCompletion;
        boolean removeAttachmentsOnCompletion;
        Integer pruneMetaDataDays;
        Integer pruneContentDays;
        boolean archive;
        boolean pruneErrored;

        boolean pruned() {
            return pruneMetaDataDays != null || pruneContentDays != null;
        }
    }

    /** The message database as a whole. */
    static final class Database {
        String type;
        Long bytes;
        Long freeBytes;
        /** Why size or free space is missing, or where the free space was measured. */
        String note;
    }

    static String describe(List<Queue> queues, List<Storage> storage, Database db, Boolean prunerEnabled, String prunerSchedule, boolean singleChannel, Instant now, ZoneId zone) {
        StringBuilder sb = new StringBuilder();
        database(sb, db, prunerEnabled, prunerSchedule);
        queues(sb, queues, singleChannel, now, zone);
        storage(sb, storage, prunerEnabled, now, zone);
        sb.append("\nCounts are for this server. Ages are from the time the message was received. The size per channel is the database's own estimate of the channel's message tables (including indexes).");
        return sb.toString();
    }

    private static void database(StringBuilder sb, Database db, Boolean prunerEnabled, String prunerSchedule) {
        sb.append("## Database\n\n");
        sb.append("- Type: ").append(db == null || db.type == null ? "unknown" : db.type).append('\n');
        sb.append("- Size: ").append(db == null || db.bytes == null ? "unknown" : size(db.bytes)).append('\n');
        if (db != null && db.freeBytes != null) {
            sb.append("- Free disk space: ").append(size(db.freeBytes)).append('\n');
        }
        if (db != null && db.note != null) {
            sb.append("- Note: ").append(db.note).append('\n');
        }
        sb.append("- Data Pruner: ");
        if (prunerEnabled == null) {
            sb.append("not installed or settings not readable; channel pruning settings may not be applied");
        } else if (prunerEnabled) {
            sb.append("enabled").append(prunerSchedule == null ? "" : " (" + prunerSchedule + ")");
        } else {
            sb.append("DISABLED: no channel is pruned, whatever its own settings say");
        }
        sb.append("\n\n");
    }

    private static void queues(StringBuilder sb, List<Queue> queues, boolean singleChannel, Instant now, ZoneId zone) {
        sb.append("## Queues\n\n");
        List<Queue> waiting = new ArrayList<>();
        int unknown = 0;
        for (Queue q : queues) {
            if (q.queued == null) {
                unknown++;
            } else if (q.queued > 0) {
                waiting.add(q);
            }
        }
        waiting.sort(Comparator.comparing((Queue q) -> q.oldestQueued == null ? Instant.MAX : q.oldestQueued).thenComparing(q -> -q.queued));
        if (waiting.isEmpty()) {
            sb.append("No messages are queued.\n");
        } else {
            sb.append("| Channel | Connector | State | Queued | Oldest queued | Age | Attempts | Last attempt | Retries | Last error |\n|---|---|---|---:|---|---|---:|---|---|---|\n");
            for (Queue q : waiting) {
                sb.append("| ").append(cell(q.channel)).append(" | ").append(q.metaDataId).append(' ').append(cell(q.connector))
                        .append(" | ").append(q.state).append(" | ").append(q.queued)
                        .append(" | ").append(date(q.oldestQueued, zone)).append(" | ").append(age(q.oldestQueued, now))
                        .append(" | ").append(q.oldestSendAttempts == null ? "" : q.oldestSendAttempts)
                        .append(" | ").append(date(q.lastAttempt, zone)).append(" | ").append(retries(q))
                        .append(" | ").append(q.lastError == null ? "" : cell(oneLine(q.lastError))).append(" |\n");
            }
            sb.append('\n');
            for (Queue q : waiting) {
                String why = stuck(q, now);
                if (why != null) {
                    sb.append("- STUCK: '").append(q.channel).append("' ").append(q.metaDataId == 0 ? "source" : "destination " + q.metaDataId + " '" + q.connector + "'").append(": ").append(why).append('\n');
                }
            }
        }
        List<Queue> destinations = new ArrayList<>();
        for (Queue q : queues) {
            if (q.metaDataId > 0) {
                destinations.add(q);
            }
        }
        if (singleChannel) {
            sb.append("\n| Connector | State | Queue | Retries | Threads |\n|---|---|---|---|---:|\n");
            for (Queue q : queues) {
                sb.append("| ").append(q.metaDataId).append(' ').append(cell(q.connector)).append(" | ").append(q.state).append(" | ").append(queueMode(q)).append(" | ").append(retries(q)).append(" | ").append(q.threads).append(" |\n");
            }
        } else {
            long on = destinations.stream().filter(q -> q.queueEnabled).count();
            sb.append('\n').append(destinations.size()).append(" destination(s): ").append(on).append(" with queueing on, ").append(destinations.size() - on).append(" without (a failure there is an error, not a retry).");
            long sourceQueues = queues.stream().filter(q -> q.metaDataId == 0 && q.queueEnabled).count();
            if (sourceQueues > 0) {
                sb.append(' ').append(sourceQueues).append(" source(s) queue before processing (respond before processing).");
            }
            sb.append(" Ask for one channel to see the queue settings of each connector.\n");
        }
        if (unknown > 0) {
            sb.append("\nQueue size unknown for ").append(unknown).append(" connector(s) (channel never deployed, or the count failed).\n");
        }
        sb.append('\n');
    }

    /** Why a queue looks stuck, or null. */
    static String stuck(Queue q, Instant now) {
        if (q.queued == null || q.queued == 0) {
            return null;
        }
        List<String> reasons = new ArrayList<>();
        if ("STOPPED".equals(q.state) || "PAUSED".equals(q.state) || "UNDEPLOYED".equals(q.state) || "DISABLED".equals(q.state)) {
            reasons.add((q.metaDataId == 0 ? "the channel is " : "it is ") + q.state.toLowerCase(Locale.ROOT) + ", so the queue is not processed");
        }
        if (q.oldestQueued != null && Duration.between(q.oldestQueued, now).compareTo(STUCK_AFTER) > 0) {
            reasons.add("the oldest message has waited " + age(q.oldestQueued, now));
        }
        if (q.lastError != null && !q.lastError.isBlank()) {
            reasons.add("last error: " + oneLine(q.lastError));
        }
        return reasons.isEmpty() ? null : String.join("; ", reasons);
    }

    private static void storage(StringBuilder sb, List<Storage> storage, Boolean prunerEnabled, Instant now, ZoneId zone) {
        sb.append("## Message storage\n\n");
        List<Storage> rows = new ArrayList<>(storage);
        rows.sort(Comparator.comparing((Storage s) -> s.messages == null ? -1 : s.messages).reversed().thenComparing(s -> s.channel, String.CASE_INSENSITIVE_ORDER));
        sb.append("| Channel | State | Messages | Est. size | Oldest | Age | Pruning | Storage |\n|---|---|---:|---:|---|---|---|---|\n");
        for (Storage s : rows) {
            sb.append("| ").append(cell(s.channel)).append(" | ").append(s.state)
                    .append(" | ").append(s.messages == null ? "?" : s.messages)
                    .append(" | ").append(s.bytes == null ? "?" : size(s.bytes))
                    .append(" | ").append(date(s.oldest, zone)).append(" | ").append(age(s.oldest, now))
                    .append(" | ").append(pruning(s)).append(" | ").append(storageMode(s)).append(" |\n");
        }
        List<Storage> unpruned = new ArrayList<>();
        long unprunedMessages = 0;
        for (Storage s : rows) {
            if (!s.pruned() && s.messages != null && s.messages > 0) {
                unpruned.add(s);
                unprunedMessages += s.messages;
            }
        }
        sb.append('\n');
        if (!unpruned.isEmpty()) {
            sb.append("NO PRUNING: ").append(unpruned.size()).append(" channel(s) keep their messages forever (").append(unprunedMessages).append(" messages): ");
            List<String> names = new ArrayList<>();
            for (Storage s : unpruned) {
                names.add("'" + s.channel + "' (" + s.messages + ")");
            }
            sb.append(String.join(", ", names)).append(".\n");
        }
        if (Boolean.FALSE.equals(prunerEnabled) && rows.stream().anyMatch(Storage::pruned)) {
            sb.append("The Data Pruner is disabled, so the pruning settings above are not applied.\n");
        }
    }

    static String pruning(Storage s) {
        if (!s.pruned()) {
            return "none";
        }
        List<String> parts = new ArrayList<>();
        if (s.pruneMetaDataDays != null) {
            parts.add("messages after " + s.pruneMetaDataDays + " d");
        }
        if (s.pruneContentDays != null) {
            parts.add("content after " + s.pruneContentDays + " d");
        }
        if (s.archive) {
            parts.add("archived first");
        }
        if (!s.pruneErrored) {
            parts.add("errored kept");
        }
        return String.join(", ", parts);
    }

    private static String storageMode(Storage s) {
        String mode = s.storageMode == null ? "" : s.storageMode.toLowerCase(Locale.ROOT);
        List<String> extra = new ArrayList<>();
        if (s.removeContentOnCompletion) {
            extra.add("content removed when done");
        }
        if (s.removeAttachmentsOnCompletion) {
            extra.add("attachments removed when done");
        }
        return extra.isEmpty() ? mode : mode + " (" + String.join(", ", extra) + ")";
    }

    private static String queueMode(Queue q) {
        if (q.metaDataId == 0) {
            return q.queueEnabled ? "on (responds before processing)" : "off";
        }
        if (!q.queueEnabled) {
            return "off";
        }
        return q.sendFirst ? "on failure" : "always";
    }

    private static String retries(Queue q) {
        if (q.metaDataId == 0) {
            return "";
        }
        String interval = (q.retryIntervalMillis % 1000 == 0 ? (q.retryIntervalMillis / 1000) + " s" : q.retryIntervalMillis + " ms");
        if (q.queueEnabled) {
            return "every " + interval + " until sent";
        }
        return q.retryCount == 0 ? "none" : q.retryCount + " × every " + interval;
    }

    static String age(Instant then, Instant now) {
        if (then == null) {
            return "";
        }
        Duration d = Duration.between(then, now);
        if (d.isNegative()) {
            d = Duration.ZERO;
        }
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();
        if (days > 0) {
            return days + " d " + hours + " h";
        }
        if (hours > 0) {
            return hours + " h " + minutes + " min";
        }
        return minutes + " min";
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = { "KB", "MB", "GB", "TB" };
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    private static String date(Instant instant, ZoneId zone) {
        return instant == null ? "" : instant.atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    /**
     * An error in one line of at most 200 characters. OIE errors start with a generic line such as
     * "TCP Sender error"; the cause follows in an "ERROR MESSAGE:" line or an exception line, so that
     * is added.
     */
    static String oneLine(String text) {
        String[] lines = text.strip().split("\\r?\\n|\\r");
        String line = lines[0].strip();
        String cause = null;
        for (int i = 1; i < lines.length && cause == null; i++) {
            String l = lines[i].strip();
            if (l.startsWith("ERROR MESSAGE:")) {
                cause = l.substring("ERROR MESSAGE:".length()).strip();
            }
        }
        for (int i = 1; i < lines.length && cause == null; i++) {
            String l = lines[i].strip();
            if (l.matches("[\\w.$]+(Exception|Error)(: .*)?")) {
                cause = l;
            }
        }
        if (cause != null && !cause.isEmpty() && !line.contains(cause)) {
            line = line + ": " + cause;
        }
        return line.length() > 200 ? line.substring(0, 200) + "…" : line;
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|");
    }
}
