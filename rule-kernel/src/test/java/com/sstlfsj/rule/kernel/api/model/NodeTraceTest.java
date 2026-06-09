package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeTraceTest {

    @Test
    void children_defaultToEmptyWhenNull() {
        NodeTrace trace = new NodeTrace("CONDITION", "AMOUNT_GT", "balance",
                true, 100, "FETCHED", null, null, null);
        assertNotNull(trace.children());
        assertTrue(trace.children().isEmpty());
    }

    @Test
    void children_areImmutable() {
        NodeTrace leaf = new NodeTrace("CONDITION", "T", "m", true, 1, "FETCHED", null, null, null);
        List<NodeTrace> mutable = new ArrayList<>(List.of(leaf));
        NodeTrace parent = new NodeTrace("AND", null, null, true, null, null, null, mutable, null);
        mutable.add(leaf);
        assertEquals(1, parent.children().size(), "构造后修改原始列表不应影响 NodeTrace");
    }

    @Test
    void children_listIsUnmodifiable() {
        NodeTrace trace = new NodeTrace("AND", null, null, true, null, null, null, List.of(), null);
        assertThrows(UnsupportedOperationException.class,
                () -> trace.children().add(
                        new NodeTrace("CONDITION", "T", "m", true, 1, "FETCHED", null, null, null)));
    }

    @Test
    void nullableFields_allowNull() {
        NodeTrace trace = new NodeTrace("AND", null, null, null, null, null, null, null, null);
        assertNull(trace.conditionType());
        assertNull(trace.metricCode());
        assertNull(trace.result());
        assertNull(trace.errorCode());
        assertNull(trace.ruleVersionId());
    }

    @Test
    void nestedChildren_areRetained() {
        NodeTrace leaf = new NodeTrace("CONDITION", "T", "m", true, 1, "FETCHED", null, null, null);
        NodeTrace parent = new NodeTrace("AND", null, null, true, null, null, null, List.of(leaf), null);
        assertEquals(1, parent.children().size());
        assertEquals(leaf, parent.children().get(0));
    }

    @Test
    void ruleVersionId_stored() {
        NodeTrace trace = new NodeTrace("CONDITION", "GT", "score",
                true, 100, "PROVIDED", null, null, 42L);
        assertEquals(42L, trace.ruleVersionId());
    }

    @Test
    void legacyNineArgConstructor_leavesExpectedValueAndDisplayLabelNull() {
        NodeTrace trace = new NodeTrace("CONDITION", "GT", "score",
                true, 100, "PROVIDED", null, null, 42L);
        assertNull(trace.expectedValue());
        assertNull(trace.displayLabel());
    }

    @Test
    void elevenArgConstructor_storesExpectedValueAndDisplayLabel() {
        NodeTrace trace = new NodeTrace("CONDITION", "GT", "score",
                true, 100, "PROVIDED", null, null, 42L,
                List.of("threshold", 0), "score>=0");
        assertEquals(List.of("threshold", 0), trace.expectedValue());
        assertEquals("score>=0", trace.displayLabel());
    }
}
