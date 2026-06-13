package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.DataType;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.OperatorSpec;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Optional;
import java.util.Set;

/**
 * STARTS_WITH 条件算子：字符串前缀匹配。params 格式：{"prefix": "..."}
 */
@ConditionType(value = ConditionTypes.STARTS_WITH, displayName = "前缀匹配", schema = ParamSpec.STRING_PREFIX)
public class StartsWithEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object prefix = node.params().get(ConditionParams.PREFIX);
        if (prefix == null) return false;
        return String.valueOf(mv.value()).startsWith(String.valueOf(prefix));
    }

    @Override
    public Optional<OperatorSpec> spec() {
        return Optional.of(OperatorSpec.builder().code(ConditionTypes.STARTS_WITH).displayName("前缀匹配")
                .requiredParamKeys(Set.of(ConditionParams.PREFIX))
                .allowedDataTypes(Set.of(DataType.STRING.tag()))
                .requiresMetric(true).build());
    }
}
