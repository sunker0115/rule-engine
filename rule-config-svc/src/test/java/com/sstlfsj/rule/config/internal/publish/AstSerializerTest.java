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
                "年龄大于18", Map.of("operator", "GT", "threshold", 18));

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
                new ConditionNode("metric.threshold", "user.age", null, Map.of("operator", "GT", "threshold", 18)),
                new ConditionNode("event.payload.compare", null, null, Map.of("field", "amount", "operator", "LTE", "value", 50000))
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
                new ConditionNode("metric.threshold", "order.count", null, Map.of("operator", "GT", "threshold", 10))
        );

        String json = serializer.toJson(ast);
        AstNode restored = serializer.fromJson(json);

        assertThat(restored).isInstanceOf(NotNode.class);
    }

    @Test
    void nested_andOrNot_roundTrip() {
        // AND(NOT(cond1), OR(cond2, cond3))
        AstNode ast = new AndNode(List.of(
                new NotNode(new ConditionNode("c.type", "m.code", null, Map.of("k", "v"))),
                new OrNode(List.of(
                        new ConditionNode("c.a", null, null, Map.of()),
                        new ConditionNode("c.b", null, null, Map.of())
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
