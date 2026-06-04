package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AstSerializerTest {

    private final AstSerializer serializer = new AstSerializer();

    @Test
    void conditionNode_roundTrip() {
        ConditionNode node = new ConditionNode("metric.threshold", "user.age",
                "年龄大于18", Map.of("operator", "GT", "threshold", 18), 0.0);

        String json = serializer.toJson(node);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(ConditionNode.class);
        ConditionNode r = (ConditionNode) restored;
        assertThat(r.conditionType()).isEqualTo("metric.threshold");
        assertThat(r.metricCode()).isEqualTo("user.age");
        assertThat(r.params()).containsKey("operator");
    }

    @Test
    void andNode_withChildren_roundTrip() {
        AstNode ast = new AndNode(List.of(
                new ConditionNode("metric.threshold", "user.age", null, Map.of("operator", "GT", "threshold", 18), 0.0),
                new ConditionNode("event.payload.compare", null, null, Map.of("field", "amount", "operator", "LTE", "value", 50000), 0.0)
        ), "年龄 AND 金额", null);

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(AndNode.class);
        AndNode r = (AndNode) restored;
        assertThat(r.children()).hasSize(2);
        assertThat(r.displayLabel()).isEqualTo("年龄 AND 金额");
    }

    @Test
    void notNode_roundTrip() {
        AstNode ast = new NotNode(
                new ConditionNode("metric.threshold", "order.count", null, Map.of("operator", "GT", "threshold", 10), 0.0)
        );

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(NotNode.class);
    }

    @Test
    void ifNode_roundTrip() {
        AstNode ast = new IfNode(
                new ConditionNode("GT", "amount", null, Map.of("threshold", 1000), 0.0),
                new DecisionLeafNode("BLOCK", "HIGH_RISK"),
                new DecisionLeafNode("PASS", "LOW_RISK")
        );

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(IfNode.class);
        IfNode r = (IfNode) restored;
        assertThat(r.condition()).isInstanceOf(ConditionNode.class);
        assertThat(((DecisionLeafNode) r.thenBranch()).decisionCode()).isEqualTo("BLOCK");
        assertThat(((DecisionLeafNode) r.elseBranch()).decisionCode()).isEqualTo("PASS");
    }

    @Test
    void decisionLeafNode_roundTrip() {
        AstNode ast = new DecisionLeafNode("REJECT", "FRAUD");

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(DecisionLeafNode.class);
        DecisionLeafNode r = (DecisionLeafNode) restored;
        assertThat(r.decisionCode()).isEqualTo("REJECT");
        assertThat(r.category()).isEqualTo("FRAUD");
    }

    @Test
    void decisionTableNode_roundTrip() {
        AstNode ast = new DecisionTableNode(
                List.of(new DecisionTableNode.Column("amount", "GT")),
                List.of(new DecisionTableNode.Row(List.of(1000), "BLOCK"),
                        new DecisionTableNode.Row(java.util.Arrays.asList((Object) null), "PASS"))
        );

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(DecisionTableNode.class);
        DecisionTableNode r = (DecisionTableNode) restored;
        assertThat(r.columns()).hasSize(1);
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).decisionCode()).isEqualTo("BLOCK");
        assertThat(r.rows().get(1).conditions().get(0)).isNull();
    }

    @Test
    void nested_andOrNot_roundTrip() {
        // AND(NOT(cond1), OR(cond2, cond3))
        AstNode ast = new AndNode(List.of(
                new NotNode(new ConditionNode("c.type", "m.code", null, Map.of("k", "v"), 0.0)),
                new OrNode(List.of(
                        new ConditionNode("c.a", null, null, Map.of(), 0.0),
                        new ConditionNode("c.b", null, null, Map.of(), 0.0)
                ), null, null)
        ), null, null);

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(AndNode.class);
        AndNode root = (AndNode) restored;
        assertThat(root.children()).hasSize(2);
        assertThat(root.children().get(0)).isInstanceOf(NotNode.class);
        assertThat(root.children().get(1)).isInstanceOf(OrNode.class);
    }
}
