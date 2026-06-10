package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DecisionTest {

    @Test
    void recordEquality_byValue() {
        Decision a = new Decision("BLOCK", "拦截", 100, 1L);
        Decision b = new Decision("BLOCK", "拦截", 100, 1L);
        assertEquals(a, b);
    }

    @Test
    void fields_areRetained() {
        Decision d = new Decision("REVIEW", "人工审核", 50, 42L);
        assertEquals("REVIEW", d.code());
        assertEquals("人工审核", d.name());
        assertEquals(50, d.priority());
        assertEquals(42L, d.fromRuleVersionId());
    }

    @Test
    void nullableFromRuleVersionId_allowsNull() {
        Decision d = new Decision("PASS", "通过", 0, null);
        assertNull(d.fromRuleVersionId());
    }

    @Test
    void category_carriedByFiveArgCtor_nullByFourArgCtor() {
        Decision withCat = new Decision("REVIEW", "", 20, 9L, "中危");
        Decision noCat = new Decision("PASS", "", 10, 9L);
        assertEquals("中危", withCat.category());
        assertNull(noCat.category());
    }

    @Test
    void actions_carriedBySixArgCtor_emptyByCompatCtors() {
        RuleVersionSnapshot.DecisionAction action =
                new RuleVersionSnapshot.DecisionAction("a1", "SEND_ALERT", 0, java.util.Map.of());
        Decision full = new Decision("REJECT", "拒绝", 10, 7L, "CAT", java.util.List.of(action));
        assertEquals(java.util.List.of(action), full.actions());
        assertEquals("CAT", full.category());

        // 4-arg / 5-arg 兼容构造：actions 空
        assertTrue(new Decision("PASS", "", 1, 7L).actions().isEmpty());
        assertTrue(new Decision("R", "n", 2, 7L, "C").actions().isEmpty());
    }
}
