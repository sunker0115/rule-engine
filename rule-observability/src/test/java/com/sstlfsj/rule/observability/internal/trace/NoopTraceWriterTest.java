package com.sstlfsj.rule.observability.internal.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NoopTraceWriterTest {

    private final NoopTraceWriter writer = new NoopTraceWriter();

    @Test
    void implementsTraceWriter() {
        assertInstanceOf(TraceWriter.class, writer);
    }

    @Test
    void write_doesNotThrow_withNormalInput() {
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null, null, null);
        assertDoesNotThrow(() -> writer.write("t1", "s1", List.of(trace)));
    }

    @Test
    void write_doesNotThrow_withEmptyList() {
        assertDoesNotThrow(() -> writer.write("t1", "s1", List.of()));
    }

    @Test
    void write_doesNotThrow_withNullFields() {
        NodeTrace trace = new NodeTrace(null, null, null, null, null, null, null, null, null, null, null);
        assertDoesNotThrow(() -> writer.write(null, null, List.of(trace)));
    }
}
