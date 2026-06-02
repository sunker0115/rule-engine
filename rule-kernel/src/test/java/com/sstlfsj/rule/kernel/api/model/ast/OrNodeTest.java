package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OrNodeTest {

    @Test
    void children_areImmutable() {
        ConditionNode leaf = new ConditionNode("T", "m", null, Map.of());
        List<AstNode> mutable = new ArrayList<>(List.of(leaf));
        OrNode node = new OrNode(mutable, "label", 1.0);
        mutable.add(leaf);
        assertEquals(1, node.children().size(), "构造后修改原始列表不应影响 OrNode");
    }

    @Test
    void children_listIsUnmodifiable() {
        OrNode node = new OrNode(List.of(), null, null);
        assertThrows(UnsupportedOperationException.class,
                () -> node.children().add(new ConditionNode("T", "m", null, Map.of())));
    }

    @Test
    void recordEquality_byValue() {
        ConditionNode leaf = new ConditionNode("T", "m", null, Map.of());
        OrNode a = new OrNode(List.of(leaf), "lbl", 0.5);
        OrNode b = new OrNode(List.of(leaf), "lbl", 0.5);
        assertEquals(a, b);
    }

    @Test
    void nullableFields_allowNull() {
        OrNode node = new OrNode(List.of(), null, null);
        assertNull(node.displayLabel());
        assertNull(node.weight());
    }
}
