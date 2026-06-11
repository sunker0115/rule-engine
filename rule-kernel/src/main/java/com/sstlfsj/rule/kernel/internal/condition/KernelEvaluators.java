package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionTypes;
import com.sstlfsj.rule.kernel.api.spi.condition.ConditionEvaluator;

import java.util.HashMap;
import java.util.Map;

/**
 * 内置 ConditionEvaluator 工厂：返回全量默认算子 Map，无 Spring 依赖。
 * rule-eval-svc 的 EvalAutoConfiguration 和 rule-sdk 的 RuleEngineClient 均通过此方法获取算子。
 */
public final class KernelEvaluators {

    private KernelEvaluators() {}

    /**
     * 返回所有内置算子的不可变 Map，key 为算子 type code（与 ConditionNode.conditionType 对应）。
     *
     * @return 不可变的内置算子 Map
     */
    public static Map<String, ConditionEvaluator> defaults() {
        Map<String, ConditionEvaluator> m = new HashMap<>();
        m.put(ConditionTypes.EQ,           new EqEvaluator());
        m.put(ConditionTypes.NEQ,          new NeqEvaluator());
        m.put(ConditionTypes.GT,           new GtEvaluator());
        m.put(ConditionTypes.GTE,          new GteEvaluator());
        m.put(ConditionTypes.LT,           new LtEvaluator());
        m.put(ConditionTypes.LTE,          new LteEvaluator());
        m.put(ConditionTypes.IN,           new InEvaluator());
        m.put(ConditionTypes.NOT_IN,       new NotInEvaluator());
        m.put(ConditionTypes.BETWEEN,      new BetweenEvaluator());
        m.put(ConditionTypes.NOT_BETWEEN,  new NotBetweenEvaluator());
        m.put(ConditionTypes.CONTAINS,     new ContainsEvaluator());
        m.put(ConditionTypes.NOT_CONTAINS, new NotContainsEvaluator());
        m.put(ConditionTypes.STARTS_WITH,  new StartsWithEvaluator());
        m.put(ConditionTypes.ENDS_WITH,    new EndsWithEvaluator());
        m.put(ConditionTypes.MATCHES,      new MatchesEvaluator());
        m.put(ConditionTypes.DATE_BEFORE,  new DateBeforeEvaluator());
        m.put(ConditionTypes.DATE_AFTER,   new DateAfterEvaluator());
        m.put(ConditionTypes.TIME_WINDOW,  new TimeWindowEvaluator());
        m.put(ConditionTypes.TIME_OCCURRED_AT, new OccurredAtEvaluator());
        return Map.copyOf(m);
    }
}
