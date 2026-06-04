package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;

import java.time.Instant;
import java.util.Map;

/** 算子测试基类，提供 EvalContext 和 ConditionNode 构造工具方法。 */
class BaseEvaluatorTest {

    EvalContext ctxWith(String metricCode, Object value) {
        MetricValue mv = new MetricValue(value, "LONG", "PROVIDED");
        RuleEvent evt = new RuleEvent("t1", "s1", "E", "u1", "id1",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", evt, null, Map.of(metricCode, mv));
    }

    EvalContext emptyCtx() {
        RuleEvent evt = new RuleEvent("t1", "s1", "E", "u1", "id1",
                Instant.now(), Map.of(), Map.of());
        return new EvalContext("t1", evt, null, Map.of());
    }

    ConditionNode node(String metricCode, String condType, Object threshold) {
        return new ConditionNode(condType, metricCode, null, Map.of("threshold", threshold), 0.0);
    }
}
