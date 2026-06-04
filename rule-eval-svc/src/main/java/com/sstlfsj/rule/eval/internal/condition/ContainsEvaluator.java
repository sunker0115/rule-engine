package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * CONTAINS 条件算子：LIST 类型 metric 中包含指定元素。
 * metric 值需为 Collection；params 格式：{"element": ...}
 */
@Component("CONTAINS")
class ContainsEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        if (!(mv.value() instanceof Collection<?> list)) return false;
        Object element = node.params().get("element");
        if (element == null) return false;
        String target = String.valueOf(element);
        return list.stream().anyMatch(v -> target.equals(String.valueOf(v)));
    }
}
