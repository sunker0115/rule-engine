package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.annotation.ConditionType;
import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ConditionParams;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.operator.ParamSpec;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

/**
 * ENDS_WITH 条件算子：字符串后缀匹配。
 * params 格式：{"suffix": "..."}
 */
@ConditionType(value = ConditionTypes.ENDS_WITH, displayName = "后缀匹配", schema = ParamSpec.STRING_SUFFIX)
public class EndsWithEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object suffix = node.params().get(ConditionParams.SUFFIX);
        if (suffix == null) return false;
        return String.valueOf(mv.value()).endsWith(String.valueOf(suffix));
    }
}
