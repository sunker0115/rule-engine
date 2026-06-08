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

    @Test
    void leaf_carriesResolvedValueAndSource() {
        ConditionOutcome o = ConditionOutcome.leaf(true, 100L, "PROVIDED");
        assertTrue(o.satisfied());
        assertFalse(o.isError());
        assertEquals(100L, o.resolvedValue());
        assertEquals("PROVIDED", o.valueSource());
    }

    @Test
    void leafError_carriesCodeValueAndSource() {
        ConditionOutcome o = ConditionOutcome.error("METRIC_FETCH_FAIL", null, "FETCHED");
        assertTrue(o.isError());
        assertEquals("METRIC_FETCH_FAIL", o.errorCode());
        assertEquals("FETCHED", o.valueSource());
    }

    @Test
    void containerOutcomes_haveNoLeafValue() {
        assertNull(ConditionOutcome.of(true).resolvedValue());
        assertNull(ConditionOutcome.of(true).valueSource());
        assertNull(ConditionOutcome.error("X").resolvedValue());
    }
}
