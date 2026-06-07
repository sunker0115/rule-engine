package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.async.EvaluationEventPublisher;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 验证 EvalServiceImpl 将 SCORECARD 结果（含 score）透传给调用方。
 * executor 路由逻辑已下沉到 EvalEngine，本测试仅验证 EvalServiceImpl 的副作用壳行为。
 */
@ExtendWith(MockitoExtension.class)
class EvalServiceImplScorecardTest {

    @Mock EvalEngine evalEngine;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalSessionWriter sessionWriter;
    @Mock DryRunTraceWriter dryRunTraceWriter;
    @Mock EvaluationEventPublisher eventPublisher;

    EvalServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new EvalServiceImpl(evalEngine, snapshotLoader,
                sessionWriter, dryRunTraceWriter, eventPublisher);
    }

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    private RuleVersionSnapshot scorecardSnapshot() {
        return new RuleVersionSnapshot(
                1L, "fraud_check", "1", null, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("HIGH_RISK", 10)),
                null, "SCORECARD");
    }

    private EvalContext ctx() {
        return new EvalContext("1", event(), null, Map.of(), Instant.parse("2024-01-01T00:00:00Z"));
    }

    private void stubPull(EvalResult engineResult) {
        when(evalEngine.match(any(RuleEvent.class))).thenReturn(List.of(scorecardSnapshot()));
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class)))
                .thenReturn(new EvalOutcome(engineResult, ctx()));
    }

    /** EvalEngine 返回带 score 的结果，EvalServiceImpl 应原样透传。 */
    @Test
    void scorecard_result_score_isPropagated() {
        Decision d = new Decision("HIGH_RISK", "", 10, 1L);
        EvalResult engineResult = new EvalResult(true, d, List.of(d), List.of(), null, List.of(), 60.0, null, null);
        stubPull(engineResult);

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertEquals(60.0, result.score());
    }

    /** MISS 时 score 为 null，EvalServiceImpl 透传。 */
    @Test
    void scorecard_miss_score_isNull() {
        stubPull(EvalResult.miss());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertNull(result.score());
    }
}
