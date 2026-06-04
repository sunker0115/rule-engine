package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MATCHES 条件算子：正则匹配（Java Pattern.matches，全串匹配）。
 * 正则非法时返回 false，不抛异常。params 格式：{"regex": "..."}
 */
public class MatchesEvaluator implements ConditionEvaluator {

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object regex = node.params().get("regex");
        if (regex == null) return false;
        try {
            return Pattern.matches(String.valueOf(regex), String.valueOf(mv.value()));
        } catch (PatternSyntaxException e) {
            return false;
        }
    }
}
