package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoopDryRunTraceWriterTest {

    @Test
    void implementsDryRunTraceWriter() {
        assertInstanceOf(DryRunTraceWriter.class, new NoopDryRunTraceWriter());
    }

    @Test
    void write_withValidArgs_doesNotThrow() {
        NoopDryRunTraceWriter writer = new NoopDryRunTraceWriter();
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null, null, 0L, null, null);
        assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void write_withNullArgs_doesNotThrow() {
        NoopDryRunTraceWriter writer = new NoopDryRunTraceWriter();
        assertDoesNotThrow(() -> writer.write(null, null, List.of()));
    }
}
