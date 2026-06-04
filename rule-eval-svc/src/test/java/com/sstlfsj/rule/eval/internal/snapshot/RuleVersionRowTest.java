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
                "[\"EVT_1\"]",
                "AST_BOOLEAN"
        );

        assertEquals(99L, row.ruleVersionId());
        assertEquals("scene_a", row.sceneCode());
        assertEquals(7L, row.tenantId());
        assertEquals("{\"type\":\"ConditionNode\"}", row.conditionAstJson());
        assertEquals("[]", row.preGatesJson());
        assertEquals("[{\"decisionCode\":\"PASS\",\"priority\":1}]", row.decisionBindingsJson());
        assertEquals("[\"EVT_1\"]", row.triggerEventTypesJson());
        assertEquals("AST_BOOLEAN", row.kind());
    }

    @Test
    void constructor_kind_scorecard() {
        RuleVersionRow row = new RuleVersionRow(
                1L, "s", 2L, "{}", "[]", "[]", "[]", "SCORECARD"
        );
        assertEquals("SCORECARD", row.kind());
    }

    @Test
    void constructor_kind_null_allowed() {
        RuleVersionRow row = new RuleVersionRow(
                1L, "s", 2L, "{}", "[]", "[]", "[]", null
        );
        assertNull(row.kind());
    }

    @Test
    void record_equality_basedOnAllFields() {
        RuleVersionRow r1 = new RuleVersionRow(1L, "s", 2L, "{}", "[]", "[]", "[]", "AST_BOOLEAN");
        RuleVersionRow r2 = new RuleVersionRow(1L, "s", 2L, "{}", "[]", "[]", "[]", "AST_BOOLEAN");
        assertEquals(r1, r2);
        assertEquals(r1.hashCode(), r2.hashCode());
    }
}
