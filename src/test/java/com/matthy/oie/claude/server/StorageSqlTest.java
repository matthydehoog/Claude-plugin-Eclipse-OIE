/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.matthy.oie.claude.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class StorageSqlTest {

    @Test
    public void messageTablesLeaveOutStatistics() {
        assertEquals(Arrays.asList("D_M12", "D_MM12", "D_MC12", "D_MCM12", "D_MA12"), StorageSql.tables(12));
    }

    @Test
    public void oldestMessageUsesThePrimaryKey() {
        assertEquals("SELECT RECEIVED_DATE FROM D_M7 WHERE ID = (SELECT MIN(ID) FROM D_M7)", StorageSql.oldestMessage(7));
    }

    @Test
    public void everyOieDatabaseTypeHasSizeQueries() {
        for (String type : new String[] { "derby", "postgres", "mysql", "oracle", "sqlserver", " Postgres " }) {
            assertNotNull(type, StorageSql.channelSize(type, 3));
            assertNotNull(type, StorageSql.databaseSize(type));
        }
        assertNull(StorageSql.channelSize("sqlite", 3));
        assertNull(StorageSql.databaseSize(null));
    }

    @Test
    public void tableNameCaseMatchesEachDatabase() {
        // PostgreSQL folds unquoted names to lower case; MySQL and SQL Server are compared in lower case.
        assertTrue(StorageSql.channelSize("postgres", 3).contains("'d_mc3'"));
        assertTrue(StorageSql.channelSize("mysql", 3).contains("LOWER(TABLE_NAME) IN ('d_m3'"));
        assertTrue(StorageSql.channelSize("sqlserver", 3).contains("LOWER(t.name) IN ('d_m3'"));
        // Oracle and Derby store them in upper case.
        assertTrue(StorageSql.channelSize("oracle", 3).contains("'D_MC3'"));
        assertTrue(StorageSql.channelSize("derby", 3).contains("'D_MC3'"));
        assertFalse(StorageSql.channelSize("postgres", 3).contains("d_ms3"));
    }

    @Test
    public void queriesOnlyReadTheCatalog() {
        for (String type : new String[] { "derby", "postgres", "mysql", "oracle", "sqlserver" }) {
            String sql = StorageSql.channelSize(type, 3) + " " + StorageSql.databaseSize(type);
            assertTrue(type, sql.startsWith("SELECT "));
            assertFalse(type, sql.matches("(?is).*\\b(insert|update|delete|drop|alter|content)\\b.*"));
        }
    }
}
