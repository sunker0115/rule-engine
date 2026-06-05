package com.sstlfsj.rule.kernel.evaluator;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.model.ast.XorNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import com.sstlfsj.rule.kernel.internal.evaluator.InterpretedExecutor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** XorNode 单测：验证"有且仅有一个子节点满足时整体为 true"的 XOR 语义。 */
class XorNodeTest {

    private static final String ALWAYS_TRUE  = "ALWAYS_TRUE";
    private static final String ALWAYS_FALSE = "ALWAYS_FALSE";

    private final ConditionEvaluator alwaysTrue  = (node, ctx) -> true;
    private final ConditionEvaluator alwaysFalse = (node, ctx) -> false;

    private EvalContext ctx() {
        RuleEvent event = new RuleEvent("t1", "scene1", "ORDER_PLACED", "u1",
                "evt-1", Instant.now(), Map.of(), null);
        return new EvalContext("t1", event, null, Map.of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private RuleVersionSnapshot snapshot(XorNode root) {
        return new RuleVersionSnapshot(1L, "scene1", "t1", root, null, null, null, "AST_BOOLEAN");
    }

    private InterpretedExecutor executor() {
        return new InterpretedExecutor(Map.of(ALWAYS_TRUE, alwaysTrue, ALWAYS_FALSE, alwaysFalse));
    }

    private ConditionNode trueNode(String metricCode) {
        return new ConditionNode(ALWAYS_TRUE, metricCode, null, Map.of(), 0.0);
    }

    private ConditionNode falseNode(String metricCode) {
        return new ConditionNode(ALWAYS_FALSE, metricCode, null, Map.of(), 0.0);
    }

    @Test
    void 恰好一个满足_命中() {
        XorNode xor = new XorNode(List.of(trueNode("m1"), falseNode("m2"), falseNode("m3")), null);
        EvalResult result = executor().execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isTrue();
    }

    @Test
    void 全部满足_不命中() {
        XorNode xor = new XorNode(List.of(trueNode("m1"), trueNode("m2")), null);
        EvalResult result = executor().execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void 全部不满足_不命中() {
        XorNode xor = new XorNode(List.of(falseNode("m1"), falseNode("m2")), null);
        EvalResult result = executor().execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void 空子节点_不命中() {
        XorNode xor = new XorNode(List.of(), null);
        EvalResult result = executor().execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }

    @Test
    void 两个满足_不命中() {
        XorNode xor = new XorNode(List.of(trueNode("m1"), trueNode("m2"), falseNode("m3")), null);
        EvalResult result = executor().execute(snapshot(xor), ctx());
        assertThat(result.ruleHit()).isFalse();
    }
}
