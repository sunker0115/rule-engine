package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ConditionTypes 值契约：= AST ConditionNode.conditionType / evaluator 注册键，不可随意改动。 */
class ConditionTypesTest {

    @Test
    void values_matchPersistedContract() {
        assertEquals("EQ", ConditionTypes.EQ);
        assertEquals("NEQ", ConditionTypes.NEQ);
        assertEquals("GT", ConditionTypes.GT);
        assertEquals("GTE", ConditionTypes.GTE);
        assertEquals("LT", ConditionTypes.LT);
        assertEquals("LTE", ConditionTypes.LTE);
        assertEquals("IN", ConditionTypes.IN);
        assertEquals("NOT_IN", ConditionTypes.NOT_IN);
        assertEquals("BETWEEN", ConditionTypes.BETWEEN);
        assertEquals("NOT_BETWEEN", ConditionTypes.NOT_BETWEEN);
        assertEquals("CONTAINS", ConditionTypes.CONTAINS);
        assertEquals("NOT_CONTAINS", ConditionTypes.NOT_CONTAINS);
        assertEquals("STARTS_WITH", ConditionTypes.STARTS_WITH);
        assertEquals("ENDS_WITH", ConditionTypes.ENDS_WITH);
        assertEquals("MATCHES", ConditionTypes.MATCHES);
        assertEquals("DATE_BEFORE", ConditionTypes.DATE_BEFORE);
        assertEquals("DATE_AFTER", ConditionTypes.DATE_AFTER);
        assertEquals("time.window", ConditionTypes.TIME_WINDOW);
        assertEquals("time.occurred_at", ConditionTypes.TIME_OCCURRED_AT);
    }

    @Test
    void constants_coverExactlyTheBuiltinRegistrationKeys() {
        Set<String> constants = Set.of(
                ConditionTypes.EQ, ConditionTypes.NEQ,
                ConditionTypes.GT, ConditionTypes.GTE, ConditionTypes.LT, ConditionTypes.LTE,
                ConditionTypes.IN, ConditionTypes.NOT_IN,
                ConditionTypes.BETWEEN, ConditionTypes.NOT_BETWEEN,
                ConditionTypes.CONTAINS, ConditionTypes.NOT_CONTAINS,
                ConditionTypes.STARTS_WITH, ConditionTypes.ENDS_WITH, ConditionTypes.MATCHES,
                ConditionTypes.DATE_BEFORE, ConditionTypes.DATE_AFTER,
                ConditionTypes.TIME_WINDOW, ConditionTypes.TIME_OCCURRED_AT);
        // 常量集合必须与内置算子注册键完全一致（不多不少）。
        assertEquals(KernelEvaluators.defaults().keySet(), constants);
    }
}
