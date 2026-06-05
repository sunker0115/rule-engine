package com.sstlfsj.rule.kernel.api.model.ast;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void jacksonAnnotation_serializesTypeField() throws Exception {
        // 验证 @JsonTypeInfo/@JsonSubTypes 直接标注在 AstNode 上后，
        // 无需 mixin 的普通 ObjectMapper 也能正确输出 "type" 字段
        ObjectMapper mapper = new ObjectMapper();
        ConditionNode node = new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0);
        String json = mapper.writeValueAsString(node);
        assertTrue(json.contains("\"type\":\"ConditionNode\""), "缺少 type 字段: " + json);
    }

    @Test
    void jacksonAnnotation_roundTrip_withPlainMapper() throws Exception {
        // 验证普通 ObjectMapper（全局 mapper 场景，如 Spring MVC）能正确往返序列化
        ObjectMapper mapper = new ObjectMapper();
        AstNode original = new AndNode(
                List.of(new ConditionNode("EQ", "status", null, Map.of("value", "ACTIVE"), 0.0)),
                null, null);
        String json = mapper.writeValueAsString(original);
        AstNode restored = mapper.readValue(json, AstNode.class);
        assertInstanceOf(AndNode.class, restored);
        assertEquals(1, ((AndNode) restored).children().size());
        assertInstanceOf(ConditionNode.class, ((AndNode) restored).children().get(0));
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
