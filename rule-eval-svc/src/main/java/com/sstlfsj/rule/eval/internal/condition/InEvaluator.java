package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * IN 条件算子：actual 的字符串值包含在 params.values 列表中。
 * params 格式：{"values": ["v1","v2",...]}
 */
@Component("IN")
class InEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object valuesObj = node.params().get("values");
        if (!(valuesObj instanceof Collection<?> values)) return false;
        String actual = String.valueOf(mv.value());
        return values.stream().anyMatch(v -> actual.equals(String.valueOf(v)));
    }
}
