package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecisionLeafNodeTest {

    @Test
    void constructor_storesFields() {
        DecisionLeafNode node = new DecisionLeafNode("BLOCK", "HIGH_RISK");
        assertEquals("BLOCK", node.decisionCode());
        assertEquals("HIGH_RISK", node.category());
    }

    @Test
    void category_canBeNull() {
        DecisionLeafNode node = new DecisionLeafNode("PASS", null);
        assertEquals("PASS", node.decisionCode());
        assertNull(node.category());
    }

    @Test
    void decisionCode_canMatchCategory() {
        DecisionLeafNode node = new DecisionLeafNode("REVIEW", "REVIEW");
        assertEquals(node.decisionCode(), node.category());
    }
}
