package com.sstlfsj.rule.kernel.api.spi.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    private static final ConditionEvaluator ALWAYS_TRUE = (node, ctx) -> true;
    private static final ConditionEvaluator ALWAYS_FALSE = (node, ctx) -> false;

    private static EvalContext buildCtx() {
        RuleEvent event = new RuleEvent("t1", "SCENE1", "PAYMENT",
                "u1", "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.<String, MetricValue>of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void evaluate_returnsTrue_whenImplementationReturnsTrue() {
        ConditionNode node = new ConditionNode("AMOUNT_GT", null, null, Map.of(), 0.0);
        assertTrue(ALWAYS_TRUE.evaluate(node, buildCtx()));
    }

    @Test
    void evaluate_returnsFalse_whenImplementationReturnsFalse() {
        ConditionNode node = new ConditionNode("AMOUNT_GT", null, null, Map.of(), 0.0);
        assertFalse(ALWAYS_FALSE.evaluate(node, buildCtx()));
    }

    @Test
    void evaluate_isFunctionalInterface() {
        // Lambda 赋值验证接口恰好只有一个抽象方法（函数式接口契约）。
        ConditionEvaluator evaluator = (node, ctx) -> node.conditionType().startsWith("AMOUNT");
        ConditionNode node = new ConditionNode("AMOUNT_GT", null, null, Map.of(), 0.0);
        assertTrue(evaluator.evaluate(node, buildCtx()));
    }
}
