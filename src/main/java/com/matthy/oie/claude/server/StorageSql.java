/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Read-only queries for oie_queues_and_storage that OIE has no API for: the size of a channel's
 * message tables, the size of the database and the oldest stored message. They read the database's
 * own catalog or a message's ID and date, never message content. Table names are built from the
 * numeric local channel ID only, so nothing from the user or Claude ends up in the SQL.
 */
final class StorageSql {

    /** Donkey's message tables per channel: messages, connector messages, content, custom metadata, attachments (D_MS, the statistics, is left out). */
    private static final String[] PREFIXES = { "D_M", "D_MM", "D_MC", "D_MCM", "D_MA" };

    private StorageSql() {
    }

    static List<String> tables(long localChannelId) {
        List<String> tables = new ArrayList<>();
        for (String prefix : PREFIXES) {
            tables.add(prefix + localChannelId);
        }
        return tables;
    }

    /** Received date of the message with the lowest ID; uses the primary key, so it is quick on any size. */
    static String oldestMessage(long localChannelId) {
        return "SELECT RECEIVED_DATE FROM D_M" + localChannelId + " WHERE ID = (SELECT MIN(ID) FROM D_M" + localChannelId + ")";
    }

    /** One number: bytes of the channel's message tables including indexes and LOBs, or null when the database type is not known. */
    static String channelSize(String databaseType, long localChannelId) {
        List<String> tables = tables(localChannelId);
        switch (type(databaseType)) {
            case "postgres":
                return "SELECT COALESCE(SUM(pg_total_relation_size(c.oid)), 0) FROM pg_class c WHERE c.relkind = 'r' AND pg_table_is_visible(c.oid) AND c.relname IN (" + list(tables, false) + ")";
            case "mysql":
                return "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND LOWER(TABLE_NAME) IN (" + list(tables, false) + ")";
            case "sqlserver":
                return "SELECT COALESCE(SUM(CAST(a.total_pages AS BIGINT)), 0) * 8192 FROM sys.tables t JOIN sys.partitions p ON p.object_id = t.object_id JOIN sys.allocation_units a ON a.container_id = p.partition_id WHERE LOWER(t.name) IN (" + list(tables, false) + ")";
            case "oracle": {
                String in = list(tables, true);
                return "SELECT NVL(SUM(bytes), 0) FROM user_segments WHERE segment_name IN (" + in + ")"
                        + " OR segment_name IN (SELECT segment_name FROM user_lobs WHERE table_name IN (" + in + "))"
                        + " OR segment_name IN (SELECT index_name FROM user_indexes WHERE table_name IN (" + in + "))";
            }
            case "derby":
                return "SELECT COALESCE(SUM(CAST(T.NUMALLOCATEDPAGES AS BIGINT) * T.PAGESIZE), 0) FROM SYS.SYSTABLES S, TABLE (SYSCS_DIAG.SPACE_TABLE(S.TABLENAME)) AS T WHERE S.TABLETYPE = 'T' AND S.TABLENAME IN (" + list(tables, true) + ")";
            default:
                return null;
        }
    }

    /** One number: bytes used by the OIE database (or schema, for Oracle), or null when not known. */
    static String databaseSize(String databaseType) {
        switch (type(databaseType)) {
            case "postgres":
                return "SELECT pg_database_size(current_database())";
            case "mysql":
                return "SELECT COALESCE(SUM(DATA_LENGTH + INDEX_LENGTH), 0) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()";
            case "sqlserver":
                return "SELECT SUM(CAST(size AS BIGINT)) * 8192 FROM sys.database_files";
            case "oracle":
                return "SELECT NVL(SUM(bytes), 0) FROM user_segments";
            case "derby":
                return "SELECT COALESCE(SUM(CAST(T.NUMALLOCATEDPAGES AS BIGINT) * T.PAGESIZE), 0) FROM SYS.SYSTABLES S, TABLE (SYSCS_DIAG.SPACE_TABLE(S.TABLENAME)) AS T WHERE S.TABLETYPE = 'T'";
            default:
                return null;
        }
    }

    /** OIE's database type names: derby, postgres, mysql, oracle, sqlserver. */
    static String type(String databaseType) {
        return databaseType == null ? "" : databaseType.trim().toLowerCase(Locale.ROOT);
    }

    private static String list(List<String> tables, boolean upper) {
        List<String> quoted = new ArrayList<>();
        for (String t : tables) {
            quoted.add("'" + (upper ? t : t.toLowerCase(Locale.ROOT)) + "'");
        }
        return String.join(", ", quoted);
    }
}
