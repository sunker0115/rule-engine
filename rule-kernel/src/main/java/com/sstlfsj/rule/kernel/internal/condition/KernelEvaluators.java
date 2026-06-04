package com.sstlfsj.rule.kernel.internal.condition;

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
        m.put("EQ",           new EqEvaluator());
        m.put("NEQ",          new NeqEvaluator());
        m.put("GT",           new GtEvaluator());
        m.put("GTE",          new GteEvaluator());
        m.put("LT",           new LtEvaluator());
        m.put("LTE",          new LteEvaluator());
        m.put("IN",           new InEvaluator());
        m.put("NOT_IN",       new NotInEvaluator());
        m.put("BETWEEN",      new BetweenEvaluator());
        m.put("NOT_BETWEEN",  new NotBetweenEvaluator());
        m.put("CONTAINS",     new ContainsEvaluator());
        m.put("NOT_CONTAINS", new NotContainsEvaluator());
        m.put("STARTS_WITH",  new StartsWithEvaluator());
        m.put("ENDS_WITH",    new EndsWithEvaluator());
        m.put("MATCHES",      new MatchesEvaluator());
        m.put("DATE_BEFORE",  new DateBeforeEvaluator());
        m.put("DATE_AFTER",   new DateAfterEvaluator());
        return Map.copyOf(m);
    }
}
