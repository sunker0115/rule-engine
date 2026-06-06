package com.sstlfsj.rule.kernel.internal.evaluator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConditionOutcomeTest {

    @Test
    void of_true_satisfied() {
        ConditionOutcome o = ConditionOutcome.of(true);
        assertTrue(o.satisfied());
        assertFalse(o.isError());
    }

    @Test
    void of_false_notSatisfied() {
        ConditionOutcome o = ConditionOutcome.of(false);
        assertFalse(o.satisfied());
        assertEquals(ConditionOutcome.Status.NOT_SATISFIED, o.status());
    }

    @Test
    void error_carriesCode() {
        ConditionOutcome o = ConditionOutcome.error("X");
        assertTrue(o.isError());
        assertEquals("X", o.errorCode());
    }
}
