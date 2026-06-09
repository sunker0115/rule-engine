package com.sstlfsj.rule.kernel.api.model;

import com.sstlfsj.rule.kernel.internal.condition.KernelEvaluators;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ConditionType 值契约：= AST ConditionNode.conditionType / evaluator 注册键，不可随意改动。 */
class ConditionTypeTest {

    @Test
    void values_matchPersistedContract() {
        assertEquals("EQ", ConditionType.EQ);
        assertEquals("NEQ", ConditionType.NEQ);
        assertEquals("GT", ConditionType.GT);
        assertEquals("GTE", ConditionType.GTE);
        assertEquals("LT", ConditionType.LT);
        assertEquals("LTE", ConditionType.LTE);
        assertEquals("IN", ConditionType.IN);
        assertEquals("NOT_IN", ConditionType.NOT_IN);
        assertEquals("BETWEEN", ConditionType.BETWEEN);
        assertEquals("NOT_BETWEEN", ConditionType.NOT_BETWEEN);
        assertEquals("CONTAINS", ConditionType.CONTAINS);
        assertEquals("NOT_CONTAINS", ConditionType.NOT_CONTAINS);
        assertEquals("STARTS_WITH", ConditionType.STARTS_WITH);
        assertEquals("ENDS_WITH", ConditionType.ENDS_WITH);
        assertEquals("MATCHES", ConditionType.MATCHES);
        assertEquals("DATE_BEFORE", ConditionType.DATE_BEFORE);
        assertEquals("DATE_AFTER", ConditionType.DATE_AFTER);
        assertEquals("time.window", ConditionType.TIME_WINDOW);
        assertEquals("time.occurred_at", ConditionType.TIME_OCCURRED_AT);
    }

    @Test
    void constants_coverExactlyTheBuiltinRegistrationKeys() {
        Set<String> constants = Set.of(
                ConditionType.EQ, ConditionType.NEQ,
                ConditionType.GT, ConditionType.GTE, ConditionType.LT, ConditionType.LTE,
                ConditionType.IN, ConditionType.NOT_IN,
                ConditionType.BETWEEN, ConditionType.NOT_BETWEEN,
                ConditionType.CONTAINS, ConditionType.NOT_CONTAINS,
                ConditionType.STARTS_WITH, ConditionType.ENDS_WITH, ConditionType.MATCHES,
                ConditionType.DATE_BEFORE, ConditionType.DATE_AFTER,
                ConditionType.TIME_WINDOW, ConditionType.TIME_OCCURRED_AT);
        // 常量集合必须与内置算子注册键完全一致（不多不少）。
        assertEquals(KernelEvaluators.defaults().keySet(), constants);
    }
}
