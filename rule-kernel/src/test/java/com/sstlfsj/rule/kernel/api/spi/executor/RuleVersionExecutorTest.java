package com.sstlfsj.rule.kernel.api.spi.executor;

import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.MetricValue;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RuleVersionExecutorTest {

    private static final RuleVersionExecutor MISS_EXECUTOR =
            (snapshot, ctx) -> EvalResult.miss();

    private static final RuleVersionExecutor HIT_EXECUTOR =
            (snapshot, ctx) -> EvalResult.hit();

    private static EvalContext buildCtx() {
        RuleEvent event = new RuleEvent("t1", "SCENE1", "PAYMENT",
                "u1", "e1", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
        return new EvalContext("t1", event, null, Map.<String, MetricValue>of(), Instant.parse("2026-06-01T00:00:00Z"));
    }

    private static RuleVersionSnapshot buildSnapshot() {
        return new RuleVersionSnapshot(1L, "SCENE1", "t1", null, null, null, null, null);
    }

    @Test
    void execute_returnsMissResult() {
        EvalResult result = MISS_EXECUTOR.execute(buildSnapshot(), buildCtx());
        assertFalse(result.ruleHit());
        assertNull(result.finalDecision());
    }

    @Test
    void execute_returnsHitResult() {
        EvalResult result = HIT_EXECUTOR.execute(buildSnapshot(), buildCtx());
        assertTrue(result.ruleHit());
        assertNull(result.finalDecision());
    }

    @Test
    void execute_isFunctionalInterface() {
        RuleVersionExecutor executor = (snapshot, ctx) -> EvalResult.miss();
        assertNotNull(executor.execute(buildSnapshot(), buildCtx()));
    }
}
