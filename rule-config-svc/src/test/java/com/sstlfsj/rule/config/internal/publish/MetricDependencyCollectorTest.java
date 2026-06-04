package com.sstlfsj.rule.config.internal.publish;

import com.sstlfsj.rule.kernel.api.model.ast.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 MetricDependencyCollector 从 AST 树中收集 metricCode。 */
class MetricDependencyCollectorTest {

    @Test
    void singleConditionNode_withMetricCode_collected() {
        AstNode ast = new ConditionNode("metric.threshold", "user.age", null, Map.of(), 0.0);
        assertThat(MetricDependencyCollector.collect(ast)).containsExactly("user.age");
    }

    @Test
    void conditionNode_withoutMetricCode_notCollected() {
        AstNode ast = new ConditionNode("event.payload.compare", null, null, Map.of(), 0.0);
        assertThat(MetricDependencyCollector.collect(ast)).isEmpty();
    }

    @Test
    void andNode_collectsAllMetricCodesFromChildren() {
        AstNode ast = new AndNode(List.of(
                new ConditionNode("metric.threshold", "user.age", null, Map.of(), 0.0),
                new ConditionNode("metric.threshold", "order.amount", null, Map.of(), 0.0)
        ), null, null);
        assertThat(MetricDependencyCollector.collect(ast)).containsExactly("user.age", "order.amount");
    }

    @Test
    void orNode_collectsAllMetricCodes() {
        AstNode ast = new OrNode(List.of(
                new ConditionNode("metric.threshold", "user.level", null, Map.of(), 0.0),
                new ConditionNode("event.payload.compare", null, null, Map.of(), 0.0)
        ), null, null);
        assertThat(MetricDependencyCollector.collect(ast)).containsExactly("user.level");
    }

    @Test
    void notNode_collectsMetricCodeFromChild() {
        AstNode ast = new NotNode(
                new ConditionNode("metric.threshold", "account.balance", null, Map.of(), 0.0)
        );
        assertThat(MetricDependencyCollector.collect(ast)).containsExactly("account.balance");
    }

    @Test
    void nestedAst_deduplicatesSameMetricCode() {
        // 两个叶子引用同一个 metricCode，结果去重
        AstNode ast = new AndNode(List.of(
                new ConditionNode("c.type", "user.age", null, Map.of("op", "GT"), 0.0),
                new ConditionNode("c.type", "user.age", null, Map.of("op", "LT"), 0.0)
        ), null, null);
        assertThat(MetricDependencyCollector.collect(ast)).containsExactly("user.age");
    }

    @Test
    void deeplyNested_andOrNot_collectsAll() {
        // AND(NOT(user.age), OR(order.amount, account.balance))
        AstNode ast = new AndNode(List.of(
                new NotNode(new ConditionNode("c", "user.age", null, Map.of(), 0.0)),
                new OrNode(List.of(
                        new ConditionNode("c", "order.amount", null, Map.of(), 0.0),
                        new ConditionNode("c", "account.balance", null, Map.of(), 0.0)
                ), null, null)
        ), null, null);
        assertThat(MetricDependencyCollector.collect(ast))
                .containsExactlyInAnyOrder("user.age", "order.amount", "account.balance");
    }

    @Test
    void scorecardRootNode_collectsMetricCodesFromConditions() {
        AstNode ast = new ScorecardRootNode(List.of(
                new ConditionNode("GT", "score", null, Map.of("threshold", 60), 0.4),
                new ConditionNode("EQ", "channel", null, Map.of("threshold", "APP"), 0.6)
        ), 0.6);
        assertThat(MetricDependencyCollector.collect(ast))
                .containsExactly("score", "channel");
    }

    @Test
    void scorecardRootNode_conditionWithoutMetricCode_notCollected() {
        AstNode ast = new ScorecardRootNode(List.of(
                new ConditionNode("EVENT", null, null, Map.of(), 1.0)
        ), 0.5);
        assertThat(MetricDependencyCollector.collect(ast)).isEmpty();
    }

    @Test
    void xorNode_collectsMetricCodesFromBothChildren() {
        // XorNode 两侧子节点的 metricCode 均应被收集
        AstNode ast = new XorNode(List.of(
                new ConditionNode("metric.threshold", "user.age", null, Map.of(), 0.0),
                new ConditionNode("metric.threshold", "order.amount", null, Map.of(), 0.0)
        ), null);
        assertThat(MetricDependencyCollector.collect(ast))
                .containsExactly("user.age", "order.amount");
    }

    @Test
    void xorNode_nestedInAnd_collectsAll() {
        // AND(XOR(user.age, order.amount), account.balance)
        AstNode ast = new AndNode(List.of(
                new XorNode(List.of(
                        new ConditionNode("c", "user.age", null, Map.of(), 0.0),
                        new ConditionNode("c", "order.amount", null, Map.of(), 0.0)
                ), null),
                new ConditionNode("c", "account.balance", null, Map.of(), 0.0)
        ), null, null);
        assertThat(MetricDependencyCollector.collect(ast))
                .containsExactlyInAnyOrder("user.age", "order.amount", "account.balance");
    }
}
