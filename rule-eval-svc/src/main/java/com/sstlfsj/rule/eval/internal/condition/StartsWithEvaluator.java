package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

/**
 * STARTS_WITH 条件算子：字符串前缀匹配。
 * params 格式：{"prefix": "..."}
 */
@Component("STARTS_WITH")
class StartsWithEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object prefix = node.params().get("prefix");
        if (prefix == null) return false;
        return String.valueOf(mv.value()).startsWith(String.valueOf(prefix));
    }
}
