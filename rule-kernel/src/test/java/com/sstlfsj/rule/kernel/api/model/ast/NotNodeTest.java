package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotNodeTest {

    @Test
    void child_isRetained() {
        ConditionNode leaf = new ConditionNode("T", "m", null, Map.of());
        NotNode node = new NotNode(leaf);
        assertSame(leaf, node.child());
    }

    @Test
    void recordEquality_byValue() {
        ConditionNode leaf = new ConditionNode("T", "m", null, Map.of());
        NotNode a = new NotNode(leaf);
        NotNode b = new NotNode(leaf);
        assertEquals(a, b);
    }

    @Test
    void nestedNotNode_wrapsAnotherAstNode() {
        ConditionNode leaf = new ConditionNode("T", "m", null, Map.of());
        NotNode inner = new NotNode(leaf);
        NotNode outer = new NotNode(inner);
        assertInstanceOf(NotNode.class, outer.child());
    }
}
