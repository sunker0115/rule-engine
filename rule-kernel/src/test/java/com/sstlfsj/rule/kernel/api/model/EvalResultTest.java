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
        assertTrue(r.actionResults().isEmpty());
    }

    @Test
    void miss_returnsFalseWithEmptyLists() {
        EvalResult r = EvalResult.miss();
        assertFalse(r.ruleHit());
        assertNull(r.finalDecision());
        assertNull(r.errorCode());
        assertTrue(r.hitDecisions().isEmpty());
        assertTrue(r.nodeTrace().isEmpty());
        assertTrue(r.actionResults().isEmpty());
    }

    @Test
    void nullLists_defaultToEmpty() {
        EvalResult r = new EvalResult(true, null, null, null, null, null, null, null, null);
        assertNotNull(r.hitDecisions());
        assertNotNull(r.nodeTrace());
        assertNotNull(r.actionResults());
    }

    @Test
    void hitDecisions_areImmutable() {
        Decision d = new Decision("BLOCK", "拦截", 100, 1L);
        List<Decision> mutable = new ArrayList<>(List.of(d));
        EvalResult r = new EvalResult(true, d, mutable, List.of(), null, List.of(), null, null, null);
        mutable.add(d);
        assertEquals(1, r.hitDecisions().size(), "构造后修改原始列表不应影响 EvalResult");
    }

    @Test
    void lists_areUnmodifiable() {
        EvalResult r = EvalResult.miss();
        assertThrows(UnsupportedOperationException.class,
                () -> r.hitDecisions().add(new Decision("X", "X", 0, null)));
        assertThrows(UnsupportedOperationException.class,
                () -> r.actionResults().add(ActionResult.notSupported()));
    }

    @Test
    void score_nullByDefault_and_canBeSet() {
        assertNull(EvalResult.hit().score());
        assertNull(EvalResult.miss().score());

        EvalResult r = new EvalResult(true, null, List.of(), List.of(), null, List.of(), 42.5, null, null);
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
        EvalResult r = new EvalResult(true, null, List.of(), List.of(), null, List.of(), null, "HIGH_RISK", null);
        assertEquals("HIGH_RISK", r.category());
        assertNull(r.decision());
    }

    @Test
    void decision_canBeSet_forDecisionTable() {
        EvalResult r = new EvalResult(true, null, List.of(), List.of(), null, List.of(), null, null, "BLOCK");
        assertNull(r.category());
        assertEquals("BLOCK", r.decision());
    }
}
