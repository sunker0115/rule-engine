package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NodeTraceTest {

    @Test
    void children_defaultToEmptyWhenNull() {
        NodeTrace trace = new NodeTrace("CONDITION", "AMOUNT_GT", "balance",
                true, 100, "FETCHED", null, null, null, null, null);
        assertNotNull(trace.children());
        assertTrue(trace.children().isEmpty());
    }

    @Test
    void children_areImmutable() {
        NodeTrace leaf = new NodeTrace("CONDITION", "T", "m", true, 1, "FETCHED", null, null, null, null, null);
        List<NodeTrace> mutable = new ArrayList<>(List.of(leaf));
        NodeTrace parent = NodeTrace.container(NodeType.AND, true, mutable, null);
        mutable.add(leaf);
        assertEquals(1, parent.children().size(), "构造后修改原始列表不应影响 NodeTrace");
    }

    @Test
    void children_listIsUnmodifiable() {
        NodeTrace trace = NodeTrace.container(NodeType.AND, true, List.of(), null);
        assertThrows(UnsupportedOperationException.class,
                () -> trace.children().add(
                        new NodeTrace("CONDITION", "T", "m", true, 1, "FETCHED", null, null, null, null, null)));
    }

    @Test
    void nullableFields_allowNull() {
        NodeTrace trace = NodeTrace.container(NodeType.AND, null, null, null);
        assertNull(trace.conditionType());
        assertNull(trace.metricCode());
        assertNull(trace.result());
        assertNull(trace.errorCode());
        assertNull(trace.ruleVersionId());
    }

    @Test
    void nestedChildren_areRetained() {
        NodeTrace leaf = new NodeTrace("CONDITION", "T", "m", true, 1, "FETCHED", null, null, null, null, null);
        NodeTrace parent = NodeTrace.container(NodeType.AND, true, List.of(leaf), null);
        assertEquals(1, parent.children().size());
        assertEquals(leaf, parent.children().get(0));
    }

    @Test
    void ruleVersionId_stored() {
        NodeTrace trace = new NodeTrace("CONDITION", "GT", "score",
                true, 100, "PROVIDED", null, null, 42L, null, null);
        assertEquals(42L, trace.ruleVersionId());
    }

    @Test
    void containerFactory_leavesLeafFieldsNull() {
        // container 工厂用于无 metric/expected/label 的容器节点，这些字段恒为 null
        NodeTrace trace = NodeTrace.container(NodeType.AND, true, List.of(), 42L);
        assertEquals(NodeType.AND.tag(), trace.nodeType());
        assertTrue(trace.result());
        assertEquals(42L, trace.ruleVersionId());
        assertNull(trace.conditionType());
        assertNull(trace.metricCode());
        assertNull(trace.actualValue());
        assertNull(trace.valueSource());
        assertNull(trace.errorCode());
        assertNull(trace.expectedValue());
        assertNull(trace.displayLabel());
    }

    @Test
    void containerFactory_withErrorCode_storesErrorCode_leafFieldsNull() {
        // 带错误码的容器工厂：errorCode 落到 trace，metric/expected/label 仍为 null
        NodeTrace trace = NodeTrace.container(NodeType.DECISION_TABLE_ROW, false,
                "METRIC_FETCH_FAIL", List.of(), 7L);
        assertEquals(NodeType.DECISION_TABLE_ROW.tag(), trace.nodeType());
        assertFalse(trace.result());
        assertEquals("METRIC_FETCH_FAIL", trace.errorCode());
        assertEquals(7L, trace.ruleVersionId());
        assertNull(trace.metricCode());
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
