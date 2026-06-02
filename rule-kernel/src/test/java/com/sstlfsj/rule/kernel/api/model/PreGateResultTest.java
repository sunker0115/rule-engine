package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PreGateResultTest {

    @Test
    void pass_returnsTrueWithNullBlockedBy() {
        PreGateResult r = PreGateResult.pass();
        assertTrue(r.passed());
        assertNull(r.blockedBy());
    }

    @Test
    void blocked_returnsFalseWithGateType() {
        PreGateResult r = PreGateResult.blocked("RATE_LIMIT");
        assertFalse(r.passed());
        assertEquals("RATE_LIMIT", r.blockedBy());
    }

    @Test
    void recordEquality_byValue() {
        assertEquals(PreGateResult.pass(), PreGateResult.pass());
        assertEquals(PreGateResult.blocked("X"), PreGateResult.blocked("X"));
        assertNotEquals(PreGateResult.pass(), PreGateResult.blocked("X"));
    }
}
