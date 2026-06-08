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

    @org.junit.jupiter.api.Test
    void category_carriedByFiveArgCtor_nullByFourArgCtor() {
        com.sstlfsj.rule.kernel.api.model.Decision withCat =
                new com.sstlfsj.rule.kernel.api.model.Decision("REVIEW", "", 20, 9L, "中危");
        com.sstlfsj.rule.kernel.api.model.Decision noCat =
                new com.sstlfsj.rule.kernel.api.model.Decision("PASS", "", 10, 9L);
        org.junit.jupiter.api.Assertions.assertEquals("中危", withCat.category());
        org.junit.jupiter.api.Assertions.assertNull(noCat.category());
    }
}
