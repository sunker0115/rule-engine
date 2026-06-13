package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalResultTest {

    @Test
    void hit_returnsTrueWithEmptyLists() {
        EvalResult r = EvalResult.hit();
        assertTrue(r.ruleHit());
        assertNull(r.finalDecision());
        assertNull(r.errorCode());
        assertTrue(r.hitDecisions().isEmpty());
        assertTrue(r.nodeTrace().isEmpty());
    }

    @Test
    void miss_returnsFalseWithEmptyLists() {
        EvalResult r = EvalResult.miss();
        assertFalse(r.ruleHit());
        assertNull(r.finalDecision());
        assertNull(r.errorCode());
        assertTrue(r.hitDecisions().isEmpty());
        assertTrue(r.nodeTrace().isEmpty());
    }

    @Test
    void missWithTrace_carriesTrace_noError() {
        NodeTrace leaf = new NodeTrace("CONDITION", "GT", "score",
                false, 1, "PROVIDED", null, null, 7L, null, 0L, null, null);
        EvalResult r = EvalResult.miss(List.of(leaf));
        assertFalse(r.ruleHit());
        assertNull(r.errorCode());
        assertEquals(1, r.nodeTrace().size());
        assertTrue(r.hitDecisions().isEmpty());
    }

    @Test
    void error_carriesErrorCode_emptyTrace() {
        EvalResult r = EvalResult.error("DECISION_TABLE_AST_TYPE_MISMATCH");
        assertFalse(r.ruleHit());
        assertEquals("DECISION_TABLE_AST_TYPE_MISMATCH", r.errorCode());
        assertTrue(r.nodeTrace().isEmpty());
        assertNull(r.finalDecision());
    }

    @Test
    void errorWithTrace_carriesErrorCodeAndTrace() {
        NodeTrace leaf = new NodeTrace("CONDITION", "GT", "score",
                false, 1, "PROVIDED", "METRIC_FETCH_FAIL", null, 7L, null, 0L, null, null);
        EvalResult r = EvalResult.error("METRIC_FETCH_FAIL", List.of(leaf));
        assertFalse(r.ruleHit());
        assertEquals("METRIC_FETCH_FAIL", r.errorCode());
        assertEquals(1, r.nodeTrace().size());
    }

    @Test
    void errorEnumOverload_storesEnumName() {
        EvalResult r = EvalResult.error(EvalErrorCode.DECISION_TABLE_AST_TYPE_MISMATCH);
        assertFalse(r.ruleHit());
        assertEquals("DECISION_TABLE_AST_TYPE_MISMATCH", r.errorCode());
        assertTrue(r.nodeTrace().isEmpty());
    }

    @Test
    void errorEnumOverloadWithTrace_storesEnumNameAndTrace() {
        NodeTrace leaf = new NodeTrace("CONDITION", "GT", "score",
                false, 1, "PROVIDED", "METRIC_FETCH_FAIL", null, 7L, null, 0L, null, null);
        EvalResult r = EvalResult.error(EvalErrorCode.METRIC_FETCH_FAIL, List.of(leaf));
        assertEquals("METRIC_FETCH_FAIL", r.errorCode());
        assertEquals(1, r.nodeTrace().size());
    }

    @Test
    void nullLists_defaultToEmpty() {
        EvalResult r = new EvalResult(true, null, null, null, null, null, null, null);
        assertNotNull(r.hitDecisions());
        assertNotNull(r.nodeTrace());
    }

    @Test
    void hitDecisions_areImmutable() {
        Decision d = new Decision("BLOCK", "拦截", 100, 1L);
        List<Decision> mutable = new ArrayList<>(List.of(d));
        EvalResult r = new EvalResult(true, d, mutable, List.of(), null, null, null, null);
        mutable.add(d);
        assertEquals(1, r.hitDecisions().size(), "构造后修改原始列表不应影响 EvalResult");
    }

    @Test
    void lists_areUnmodifiable() {
        EvalResult r = EvalResult.miss();
        assertThrows(UnsupportedOperationException.class,
                () -> r.hitDecisions().add(new Decision("X", "X", 0, null)));
    }

    @Test
    void score_nullByDefault_and_canBeSet() {
        assertNull(EvalResult.hit().score());
        assertNull(EvalResult.miss().score());

        EvalResult r = new EvalResult(true, null, List.of(), List.of(), null, 42.5, null, null);
        assertEquals(42.5, r.score());
    }

    @Test
    void category_and_decision_nullByDefault() {
        assertNull(EvalResult.hit().category());
        assertNull(EvalResult.hit().decision());
        assertNull(EvalResult.miss().category());
        assertNull(EvalResult.miss().decision());
    }

    @Test
    void category_canBeSet_forDecisionTree() {
        EvalResult r = new EvalResult(true, null, List.of(), List.of(), null, null, "HIGH_RISK", null);
        assertEquals("HIGH_RISK", r.category());
        assertNull(r.decision());
    }

    @Test
    void decision_canBeSet_forDecisionTable() {
        EvalResult r = new EvalResult(true, null, List.of(), List.of(), null, null, null, "BLOCK");
        assertNull(r.category());
        assertEquals("BLOCK", r.decision());
    }
}
