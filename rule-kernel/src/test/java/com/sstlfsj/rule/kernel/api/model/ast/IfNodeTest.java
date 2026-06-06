package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class IfNodeTest {

    private static final ConditionNode COND = new ConditionNode("GT", "amount", null, Map.of(), 0.0);
    private static final DecisionLeafNode THEN = new DecisionLeafNode("BLOCK", "HIGH_RISK");
    private static final DecisionLeafNode ELSE = new DecisionLeafNode("PASS", "LOW_RISK");

    @Test
    void constructor_storesAllFields() {
        IfNode node = new IfNode(COND, THEN, ELSE);
        assertSame(COND, node.condition());
        assertSame(THEN, node.thenBranch());
        assertSame(ELSE, node.elseBranch());
    }

    @Test
    void elseBranch_canBeNull() {
        IfNode node = new IfNode(COND, THEN, null);
        assertNull(node.elseBranch());
    }

    @Test
    void nestedIfNode_isValid() {
        ConditionNode inner = new ConditionNode("LT", "score", null, Map.of(), 0.0);
        IfNode nested = new IfNode(inner, THEN, null);
        IfNode outer = new IfNode(COND, nested, ELSE);
        assertInstanceOf(IfNode.class, outer.thenBranch());
    }
}
