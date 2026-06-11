package com.sstlfsj.rule.kernel.internal.condition;

import com.sstlfsj.rule.kernel.api.model.ConditionType;
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
        m.put(ConditionType.EQ,           new EqEvaluator());
        m.put(ConditionType.NEQ,          new NeqEvaluator());
        m.put(ConditionType.GT,           new GtEvaluator());
        m.put(ConditionType.GTE,          new GteEvaluator());
        m.put(ConditionType.LT,           new LtEvaluator());
        m.put(ConditionType.LTE,          new LteEvaluator());
        m.put(ConditionType.IN,           new InEvaluator());
        m.put(ConditionType.NOT_IN,       new NotInEvaluator());
        m.put(ConditionType.BETWEEN,      new BetweenEvaluator());
        m.put(ConditionType.NOT_BETWEEN,  new NotBetweenEvaluator());
        m.put(ConditionType.CONTAINS,     new ContainsEvaluator());
        m.put(ConditionType.NOT_CONTAINS, new NotContainsEvaluator());
        m.put(ConditionType.STARTS_WITH,  new StartsWithEvaluator());
        m.put(ConditionType.ENDS_WITH,    new EndsWithEvaluator());
        m.put(ConditionType.MATCHES,      new MatchesEvaluator());
        m.put(ConditionType.DATE_BEFORE,  new DateBeforeEvaluator());
        m.put(ConditionType.DATE_AFTER,   new DateAfterEvaluator());
        m.put(ConditionType.TIME_WINDOW,  new TimeWindowEvaluator());
        m.put(ConditionType.TIME_OCCURRED_AT, new OccurredAtEvaluator());
        return Map.copyOf(m);
    }
}
