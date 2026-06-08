package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EvalOutcomeTest {

    @Test
    void accessors_returnConstructorArgs() {
        EvalResult result = EvalResult.hit();
        EvalContext ctx = new EvalContext("t1", null, null, java.util.Map.of(), java.time.Instant.EPOCH);

        EvalOutcome outcome = new EvalOutcome(result, ctx);

        assertSame(result, outcome.result());
        assertSame(ctx, outcome.context());
        assertNull(outcome.blockedBy());   // 2 参便捷构造器 blockedBy 默认 null
    }

    @Test
    void context_mayBeNull_forEarlyMiss() {
        EvalOutcome outcome = new EvalOutcome(EvalResult.miss(), null);

        assertFalse(outcome.result().ruleHit());
        assertNull(outcome.context());
    }

    @Test
    void blockedBy_carriesPreGateReason_forBlockedOutcome() {
        EvalOutcome outcome = new EvalOutcome(EvalResult.miss(), null, "ROLLOUT");

        assertFalse(outcome.result().ruleHit());
        assertEquals("ROLLOUT", outcome.blockedBy());
    }
}