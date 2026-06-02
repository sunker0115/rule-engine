package com.sstlfsj.rule.kernel.api.model.ast;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AstNodeTest {

    @Test
    void andNode_isInstanceOfAstNode() {
        AndNode node = new AndNode(List.of(), null, null);
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void orNode_isInstanceOfAstNode() {
        OrNode node = new OrNode(List.of(), null, null);
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void notNode_isInstanceOfAstNode() {
        ConditionNode leaf = new ConditionNode("TYPE", "code", null, Map.of());
        NotNode node = new NotNode(leaf);
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void conditionNode_isInstanceOfAstNode() {
        ConditionNode node = new ConditionNode("TYPE", "code", null, Map.of());
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void switchPatternMatching_coversAllPermits() {
        AstNode node = new ConditionNode("T", "m", null, Map.of());
        // 确认 sealed interface 的 switch 能完整覆盖所有子类型
        String result = switch (node) {
            case AndNode a -> "and";
            case OrNode o -> "or";
            case NotNode n -> "not";
            case ConditionNode c -> "condition";
        };
        assertEquals("condition", result);
    }
}
