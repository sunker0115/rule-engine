package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Collection;

/**
 * NOT_CONTAINS：LIST 类型 metric 中不包含指定元素。list 为 null 时返回 true。
 */
@ConditionType(value = ConditionTypes.NOT_CONTAINS, displayName = "集合不包含", schema = ParamSpec.LIST_MEMBERSHIP)
public class NotContainsEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return true;
        if (!(mv.value() instanceof Collection<?> list)) return true;
        Object element = node.params().get(ConditionParams.ELEMENT);
        if (element == null) return true;
        String target = String.valueOf(element);
        return list.stream().noneMatch(v -> target.equals(String.valueOf(v)));
    }
}
