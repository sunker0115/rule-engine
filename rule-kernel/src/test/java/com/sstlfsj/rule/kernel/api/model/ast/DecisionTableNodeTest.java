package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionTableNodeTest {

    @Test
    void constructor_storesColumnsAndRows() {
        var col1 = new DecisionTableNode.Column("amount", "GT");
        var col2 = new DecisionTableNode.Column("country", "IN");
        var row = new DecisionTableNode.Row(List.of(1000, List.of("CN", "HK")), "BLOCK");

        DecisionTableNode node = new DecisionTableNode(List.of(col1, col2), List.of(row));

        assertEquals(2, node.columns().size());
        assertEquals(1, node.rows().size());
        assertEquals("amount", node.columns().get(0).metricCode());
        assertEquals("GT", node.columns().get(0).operator());
        assertEquals("BLOCK", node.rows().get(0).decisionCode());
    }

    @Test
    void row_withNullCondition_representsWildcard() {
        var col = new DecisionTableNode.Column("amount", "GT");
        var row = new DecisionTableNode.Row(java.util.Arrays.asList((Object) null), "PASS");

        DecisionTableNode node = new DecisionTableNode(List.of(col), List.of(row));
        assertNull(node.rows().get(0).conditions().get(0));
    }

    @Test
    void emptyTable_isValid() {
        DecisionTableNode node = new DecisionTableNode(List.of(), List.of());
        assertTrue(node.columns().isEmpty());
        assertTrue(node.rows().isEmpty());
    }

    @Test
    void column_dataType_frozenSetAndDraftDefaultsNull() {
        // B22：3 参冻结构造带 dataType；2 参草稿便利构造 dataType 默认 null
        var frozen = new DecisionTableNode.Column("amount", "GT", "LONG");
        var draft = new DecisionTableNode.Column("amount", "GT");
        assertEquals("LONG", frozen.dataType());
        assertNull(draft.dataType());
    }
}
