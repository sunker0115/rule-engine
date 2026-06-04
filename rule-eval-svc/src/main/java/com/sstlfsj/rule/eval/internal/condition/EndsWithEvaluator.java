package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

/**
 * ENDS_WITH 条件算子：字符串后缀匹配。
 * params 格式：{"suffix": "..."}
 */
@Component("ENDS_WITH")
class EndsWithEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object suffix = node.params().get("suffix");
        if (suffix == null) return false;
        return String.valueOf(mv.value()).endsWith(String.valueOf(suffix));
    }
}
