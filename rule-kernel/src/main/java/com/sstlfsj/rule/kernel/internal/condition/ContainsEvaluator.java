package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * CONTAINS 条件算子：LIST 类型 metric 中包含指定元素。
 * metric 值需为 Collection；params 格式：{"element": ...}
 */
public class ContainsEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        if (!(mv.value() instanceof Collection<?> list)) return false;
        Object element = node.params().get(ConditionParams.ELEMENT);
        if (element == null) return false;
        String target = String.valueOf(element);
        return list.stream().anyMatch(v -> target.equals(String.valueOf(v)));
    }

    @Override
    public Optional<OperatorSpec> spec() {
        return Optional.of(OperatorSpec.builder().code(ConditionTypes.CONTAINS).displayName("集合包含")
                .requiredParamKeys(Set.of(ConditionParams.ELEMENT))
                .allowedDataTypes(Set.of(DataType.LIST.tag()))
                .requiresMetric(true).build());
    }
}
