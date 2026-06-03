package com.sstlfsj.rule.eval.internal.snapshot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RuleVersionRowTest {

    @Test
    void constructor_storesAllFields() {
        RuleVersionRow row = new RuleVersionRow(
                99L,
                "scene_a",
                7L,
                "{\"type\":\"ConditionNode\"}",
                "[]",
                "[{\"decisionCode\":\"PASS\",\"priority\":1}]",
                "[\"EVT_1\"]"
        );

        assertEquals(99L, row.ruleVersionId());
        assertEquals("scene_a", row.sceneCode());
        assertEquals(7L, row.tenantId());
        assertEquals("{\"type\":\"ConditionNode\"}", row.conditionAstJson());
        assertEquals("[]", row.preGatesJson());
        assertEquals("[{\"decisionCode\":\"PASS\",\"priority\":1}]", row.decisionBindingsJson());
        assertEquals("[\"EVT_1\"]", row.triggerEventTypesJson());
    }

    @Test
    void record_equality_basedOnAllFields() {
        RuleVersionRow r1 = new RuleVersionRow(1L, "s", 2L, "{}", "[]", "[]", "[]");
        RuleVersionRow r2 = new RuleVersionRow(1L, "s", 2L, "{}", "[]", "[]", "[]");
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }
}
