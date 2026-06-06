package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ScorecardRootNodeTest {

    @Test
    void nullConditions_treatedAsEmptyList() {
        ScorecardRootNode node = new ScorecardRootNode(null, 0.6);
        assertNotNull(node.conditions());
        assertTrue(node.conditions().isEmpty());
    }

    @Test
    void conditions_areImmutable() {
        List<ConditionNode> mutable = new ArrayList<>();
        mutable.add(new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4));
        ScorecardRootNode node = new ScorecardRootNode(mutable, 0.6);
        mutable.add(new ConditionNode("LT", "score", null, Map.of("threshold", 100), 0.6));
        assertEquals(1, node.conditions().size(), "构造后修改原始列表不应影响 ScorecardRootNode");
    }

    @Test
    void conditions_listIsUnmodifiable() {
        ScorecardRootNode node = new ScorecardRootNode(
                List.of(new ConditionNode("GT", "score", null, Map.of(), 0.5)), 0.6);
        assertThrows(UnsupportedOperationException.class,
                () -> node.conditions().add(new ConditionNode("EQ", "x", null, Map.of(), 0.0)));
    }

    @Test
    void threshold_retainsSpecifiedValue() {
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 0.75);
        assertEquals(0.75, node.threshold(), 1e-9);
    }

    @Test
    void recordEquality_byValue() {
        ConditionNode cond = new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4);
        ScorecardRootNode a = new ScorecardRootNode(List.of(cond), 0.6);
        ScorecardRootNode b = new ScorecardRootNode(List.of(cond), 0.6);
        assertEquals(a, b);
    }

    @Test
    void implementsAstNode() {
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 0.5);
        assertInstanceOf(AstNode.class, node);
    }
}
