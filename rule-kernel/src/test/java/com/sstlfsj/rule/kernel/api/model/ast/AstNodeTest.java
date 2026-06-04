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
        ConditionNode leaf = new ConditionNode("TYPE", "code", null, Map.of(), 0.0);
        NotNode node = new NotNode(leaf);
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void conditionNode_isInstanceOfAstNode() {
        ConditionNode node = new ConditionNode("TYPE", "code", null, Map.of(), 0.0);
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void scorecardRootNode_isInstanceOfAstNode() {
        ScorecardRootNode node = new ScorecardRootNode(List.of(), 0.6);
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void ifNode_isInstanceOfAstNode() {
        ConditionNode cond = new ConditionNode("GT", "amount", null, Map.of(), 0.0);
        DecisionLeafNode leaf = new DecisionLeafNode("BLOCK", "HIGH_RISK");
        IfNode node = new IfNode(cond, leaf, null);
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void decisionLeafNode_isInstanceOfAstNode() {
        DecisionLeafNode node = new DecisionLeafNode("PASS", "LOW_RISK");
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void decisionTableNode_isInstanceOfAstNode() {
        DecisionTableNode node = new DecisionTableNode(List.of(), List.of());
        assertInstanceOf(AstNode.class, node);
    }

    @Test
    void switchPatternMatching_coversAllPermits() {
        AstNode node = new ConditionNode("T", "m", null, Map.of(), 0.0);
        // 确认 sealed interface 的 switch 能完整覆盖所有子类型
        String result = switch (node) {
            case AndNode a -> "and";
            case OrNode o -> "or";
            case NotNode n -> "not";
            case ConditionNode c -> "condition";
            case ScorecardRootNode s -> "scorecard";
            case XorNode x -> "xor";
            case IfNode i -> "if";
            case DecisionLeafNode d -> "leaf";
            case DecisionTableNode t -> "table";
        };
        assertEquals("condition", result);
    }
}
