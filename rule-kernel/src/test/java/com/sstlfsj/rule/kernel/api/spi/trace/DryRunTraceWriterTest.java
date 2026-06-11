package com.sstlfsj.rule.kernel.api.spi.trace;

import com.sstlfsj.rule.kernel.api.model.NodeTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DryRunTraceWriterTest {

    @Test
    void write_receivesCorrectArguments() {
        List<String> capturedTenant = new ArrayList<>();
        List<String> capturedSession = new ArrayList<>();
        List<List<NodeTrace>> capturedTraces = new ArrayList<>();

        DryRunTraceWriter writer = (tenantId, sessionId, traces) -> {
            capturedTenant.add(tenantId);
            capturedSession.add(sessionId);
            capturedTraces.add(traces);
        };

        NodeTrace trace = new NodeTrace("CONDITION", "AMOUNT_GT", null,
                true, 100, "PROVIDED", null, null, null, null, null);
        writer.write("t1", "sess-001", List.of(trace));

        assertEquals("t1", capturedTenant.get(0));
        assertEquals("sess-001", capturedSession.get(0));
        assertEquals(1, capturedTraces.get(0).size());
    }

    @Test
    void write_isFunctionalInterface() {
        DryRunTraceWriter writer = (tenantId, sessionId, traces) -> {};
        assertDoesNotThrow(() -> writer.write("t1", "s1", List.of()));
    }

    @Test
    void noopDryRunTraceWriter_implementsInterface() {
        assertInstanceOf(DryRunTraceWriter.class, new NoopDryRunTraceWriter());
    }

    @Test
    void noopDryRunTraceWriter_doesNotThrow_withNormalInput() {
        NodeTrace trace = new NodeTrace("LEAF", "AMOUNT_GT", "revenue", true, 100, "DB", null, null, null, null, null);
        assertDoesNotThrow(() -> new NoopDryRunTraceWriter().write("t1", "s1", List.of(trace)));
    }

    @Test
    void noopDryRunTraceWriter_doesNotThrow_withEmptyList() {
        assertDoesNotThrow(() -> new NoopDryRunTraceWriter().write("t1", "s1", List.of()));
    }

    @Test
    void noopDryRunTraceWriter_doesNotThrow_withNullFields() {
        assertDoesNotThrow(() -> new NoopDryRunTraceWriter().write(null, null, List.of()));
    }
}
