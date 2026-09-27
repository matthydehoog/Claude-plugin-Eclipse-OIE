package com.matthy.oie.claude.engine;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

public class UsageTest {

    @Test
    public void addsUpTheUsageReport() throws Exception {
        String page = "{\"data\":[{\"starting_at\":\"2026-09-01T00:00:00Z\",\"results\":[{\"uncached_input_tokens\":1000,\"cache_read_input_tokens\":20000,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":3000,\"ephemeral_1h_input_tokens\":500},\"output_tokens\":400}]},"
                + "{\"starting_at\":\"2026-09-02T00:00:00Z\",\"results\":[{\"uncached_input_tokens\":200,\"cache_read_input_tokens\":5000,"
                + "\"cache_creation\":{\"ephemeral_5m_input_tokens\":0},\"output_tokens\":100}]},"
                + "{\"starting_at\":\"2026-09-03T00:00:00Z\",\"results\":[]}],\"has_more\":false}";
        long[] totals = new long[4];

        SdkSpendReader.addUsage(new ObjectMapper().readTree(page), totals);

        assertArrayEquals(new long[] { 1200, 25000, 3500, 500 }, totals);
    }

    @Test
    public void describesAQuestion() {
        SdkModelLoop.Usage usage = new SdkModelLoop.Usage();
        usage.add(1000, 0, 18450, 300);
        usage.add(204, 18450, 0, 312);

        assertEquals("Claude Assistant usage: conversation 3fa1 question, 2 calls: input 1.204 (cache read 18.450, cache write 18.450), output 612 tokens",
                usage.describe("3fa1"));
    }
}
