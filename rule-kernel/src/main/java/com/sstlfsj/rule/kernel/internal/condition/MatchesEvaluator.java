package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * MATCHES 条件算子：正则全串匹配。正则非法时返回 false，不抛异常。params 格式：{"regex": "..."}
 * 正则来自不可变规则配置（distinct 数有限），编译产物按 regex 串缓存，避免每次评估重编译
 * （{@code Pattern.matches} 每次调用都重新编译，是 MATCHES 的主要成本）。
 */
public class MatchesEvaluator implements ConditionEvaluator {

    /** regex 串 → 编译产物；非法正则缓存为 empty（负缓存，避免重复编译已知非法正则）。 */
    private final ConcurrentHashMap<String, Optional<Pattern>> patternCache = new ConcurrentHashMap<>();

    @Override
    public boolean evaluate(ConditionNode node, EvalContext ctx) {
        MetricValue mv = ctx.getMetric(node.metricCode());
        if (mv == null) return false;
        Object regex = node.params().get("regex");
        if (regex == null) return false;
        Optional<Pattern> pattern = patternCache.computeIfAbsent(String.valueOf(regex), MatchesEvaluator::compileQuietly);
        return pattern.isPresent() && pattern.get().matcher(String.valueOf(mv.value())).matches();
    }

    /** 编译正则；非法时返回 empty（语义同原 {@code PatternSyntaxException → false}）。 */
    private static Optional<Pattern> compileQuietly(String regex) {
        try {
            return Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            return Optional.empty();
        }
    }
}
