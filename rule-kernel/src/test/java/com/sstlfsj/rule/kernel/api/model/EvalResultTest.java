package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvalResultTest {

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
        EvalResult r = new EvalResult(true, null, null, null, null, null);
        assertNotNull(r.hitDecisions());
        assertNotNull(r.nodeTrace());
        assertNotNull(r.actionResults());
    }

    @Test
    void hitDecisions_areImmutable() {
        Decision d = new Decision("BLOCK", "拦截", 100, 1L);
        List<Decision> mutable = new ArrayList<>(List.of(d));
        EvalResult r = new EvalResult(true, d, mutable, List.of(), null, List.of());
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
}
