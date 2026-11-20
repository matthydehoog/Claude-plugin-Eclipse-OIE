/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class QueueStorageReportTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    private static QueueStorageReport.Queue destination(String channel, int metaDataId, String state, long queued, Duration age) {
        QueueStorageReport.Queue q = new QueueStorageReport.Queue();
        q.channel = channel;
        q.channelId = channel + "-id";
        q.connector = "Dest " + metaDataId;
        q.metaDataId = metaDataId;
        q.state = state;
        q.queueEnabled = true;
        q.retryIntervalMillis = 10_000;
        q.queued = queued;
        q.oldestQueued = age == null ? null : NOW.minus(age);
        return q;
    }

    private static QueueStorageReport.Storage storage(String channel, Long messages, Integer pruneDays) {
        QueueStorageReport.Storage s = new QueueStorageReport.Storage();
        s.channel = channel;
        s.channelId = channel + "-id";
        s.state = "STARTED";
        s.messages = messages;
        s.pruneMetaDataDays = pruneDays;
        s.pruneErrored = true;
        s.storageMode = "DEVELOPMENT";
        return s;
    }

    private static QueueStorageReport.Database postgres() {
        QueueStorageReport.Database db = new QueueStorageReport.Database();
        db.type = "postgres";
        db.bytes = 5L * 1024 * 1024 * 1024;
        return db;
    }

    private static String report(List<QueueStorageReport.Queue> queues, List<QueueStorageReport.Storage> storage, Boolean pruner) {
        return QueueStorageReport.describe(queues, storage, postgres(), pruner, "daily at 02:00", false, NOW, ZoneOffset.UTC);
    }

    @Test
    public void stoppedDestinationWithQueueIsReportedWithAgeOfOldestMessage() {
        QueueStorageReport.Queue q = destination("ADT out", 2, "STOPPED", 37, Duration.ofDays(3).plusHours(4));
        q.lastError = "java.net.ConnectException: Connection refused\n\tat java.base/sun.nio...";
        String text = report(Collections.singletonList(q), Collections.emptyList(), true);

        assertTrue(text, text.contains("| ADT out | 2 Dest 2 | STOPPED | 37 | 2026-11-12 08:00 | 3 d 4 h |"));
        assertTrue(text, text.contains("STUCK: 'ADT out' destination 2 'Dest 2': it is stopped, so the queue is not processed; the oldest message has waited 3 d 4 h; last error: java.net.ConnectException: Connection refused"));
        assertFalse("only the first line of an error", text.contains("sun.nio"));
    }

    @Test
    public void errorSummaryAddsTheCauseOfAGenericOieError() {
        assertEquals("TCP Sender error: Error connecting to 127.0.0.1:1",
                QueueStorageReport.oneLine("TCP Sender error\nERROR MESSAGE: Error connecting to 127.0.0.1:1\njava.net.ConnectException: Connection refused\n\tat sun.nio.ch.Net.connect0"));
        assertEquals("HTTP Sender error: java.net.UnknownHostException: lab.example",
                QueueStorageReport.oneLine("HTTP Sender error\n\njava.net.UnknownHostException: lab.example\n\tat java.base/java.net.InetAddress"));
        assertEquals("Connection refused", QueueStorageReport.oneLine("  Connection refused  "));
    }

    @Test
    public void recentQueueOnRunningDestinationIsNotStuck() {
        QueueStorageReport.Queue q = destination("Lab", 1, "STARTED", 3, Duration.ofMinutes(5));
        assertNull(QueueStorageReport.stuck(q, NOW));
        String text = report(Collections.singletonList(q), Collections.emptyList(), true);
        assertTrue(text, text.contains("| Lab | 1 Dest 1 | STARTED | 3 |"));
        assertFalse(text, text.contains("STUCK"));
    }

    @Test
    public void oldQueueOnRunningDestinationIsStuck() {
        QueueStorageReport.Queue q = destination("Lab", 1, "STARTED", 3, Duration.ofHours(2));
        assertEquals("the oldest message has waited 2 h 0 min", QueueStorageReport.stuck(q, NOW));
    }

    @Test
    public void emptyQueuesSayNothingIsQueued() {
        QueueStorageReport.Queue q = destination("Lab", 1, "STARTED", 0, null);
        QueueStorageReport.Queue off = destination("Lab", 2, "STARTED", 0, null);
        off.queueEnabled = false;
        String text = report(Arrays.asList(q, off), Collections.emptyList(), true);
        assertTrue(text, text.contains("No messages are queued."));
        assertTrue(text, text.contains("2 destination(s): 1 with queueing on, 1 without"));
    }

    @Test
    public void channelsWithoutPruningAreListedWithTheirMessageCount() {
        String text = report(Collections.emptyList(), Arrays.asList(storage("ADT in", 120_000L, null), storage("Lab", 500L, 30), storage("Empty", 0L, null)), true);

        assertTrue(text, text.contains("NO PRUNING: 1 channel(s) keep their messages forever (120000 messages): 'ADT in' (120000)."));
        assertTrue(text, text.contains("| ADT in | STARTED | 120000 |"));
        assertTrue(text, text.contains("| Lab | STARTED | 500 | ? |  |  | messages after 30 d | development |"));
        assertTrue("largest first", text.indexOf("| ADT in |") < text.indexOf("| Lab |"));
    }

    @Test
    public void disabledPrunerIsCalledOut() {
        String text = report(Collections.emptyList(), Collections.singletonList(storage("Lab", 500L, 30)), false);
        assertTrue(text, text.contains("Data Pruner: DISABLED"));
        assertTrue(text, text.contains("The Data Pruner is disabled, so the pruning settings above are not applied."));
    }

    @Test
    public void databaseSectionShowsTypeSizeAndSchedule() {
        String text = report(Collections.emptyList(), Collections.emptyList(), true);
        assertTrue(text, text.contains("- Type: postgres"));
        assertTrue(text, text.contains("- Size: 5.0 GB"));
        assertTrue(text, text.contains("- Data Pruner: enabled (daily at 02:00)"));
    }

    @Test
    public void singleChannelListsQueueSettingsPerConnector() {
        QueueStorageReport.Queue source = destination("Lab", 0, "STARTED", 0, null);
        source.queueEnabled = false;
        QueueStorageReport.Queue retry = destination("Lab", 1, "STARTED", 0, null);
        retry.queueEnabled = false;
        retry.retryCount = 3;
        QueueStorageReport.Queue onFailure = destination("Lab", 2, "STARTED", 0, null);
        onFailure.sendFirst = true;
        String text = QueueStorageReport.describe(Arrays.asList(source, retry, onFailure), Collections.emptyList(), postgres(), true, null, true, NOW, ZoneOffset.UTC);

        assertTrue(text, text.contains("| 0 Dest 0 | STARTED | off |  | 1 |"));
        assertTrue(text, text.contains("| 1 Dest 1 | STARTED | off | 3 × every 10 s | 1 |"));
        assertTrue(text, text.contains("| 2 Dest 2 | STARTED | on failure | every 10 s until sent | 1 |"));
    }

    @Test
    public void pruningDescribesAllSettings() {
        QueueStorageReport.Storage s = storage("Lab", 1L, 90);
        s.pruneContentDays = 7;
        s.archive = true;
        s.pruneErrored = false;
        assertEquals("messages after 90 d, content after 7 d, archived first, errored kept", QueueStorageReport.pruning(s));
    }

    @Test
    public void sizesAndAges() {
        assertEquals("512 B", QueueStorageReport.size(512));
        assertEquals("1.5 KB", QueueStorageReport.size(1536));
        assertEquals("120 MB", QueueStorageReport.size(120L * 1024 * 1024));
        assertEquals("25 min", QueueStorageReport.age(NOW.minus(Duration.ofMinutes(25)), NOW));
        assertEquals("0 min", QueueStorageReport.age(NOW.plusSeconds(30), NOW));
    }

    @Test
    public void pipesInNamesDoNotBreakTheTable() {
        String text = report(Collections.emptyList(), Collections.singletonList(storage("A|B", 1L, 1)), true);
        assertTrue(text, text.contains("| A\\|B |"));
    }
}
