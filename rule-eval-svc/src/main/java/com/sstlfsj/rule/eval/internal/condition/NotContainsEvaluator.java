package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * NOT_CONTAINS 条件算子：LIST 类型 metric 中不包含指定元素。
 * list 为 null 时返回 true；params 格式：{"element": ...}
 */
@Component("NOT_CONTAINS")
class NotContainsEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return true;
        if (!(mv.value() instanceof Collection<?> list)) return true;
        Object element = node.params().get("element");
        if (element == null) return true;
        String target = String.valueOf(element);
        return list.stream().noneMatch(v -> target.equals(String.valueOf(v)));
    }
}
