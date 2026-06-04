package com.sstlfsj.rule.eval.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;
import org.springframework.stereotype.Component;

import java.util.Collection;

/**
 * NOT_IN 条件算子：actual 的字符串值不在 params.values 列表中。
 * value 为 null（metric 缺失）时返回 true（不在列表内）。
 * params 格式：{"values": ["v1","v2",...]}
 */
@Component("NOT_IN")
class NotInEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return true;
        Object valuesObj = node.params().get("values");
        if (!(valuesObj instanceof Collection<?> values)) return true;
        String actual = String.valueOf(mv.value());
        return values.stream().noneMatch(v -> actual.equals(String.valueOf(v)));
    }
}
