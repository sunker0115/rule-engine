package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.kernel.internal.context.EvalContextAssembler;
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
    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;
    @Mock DryRunTraceWriter dryRunTraceWriter;
    @Mock ActionDispatchService actionDispatchService;
    @Mock EvalContextAssembler contextAssembler;

    EvalServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new EvalServiceImpl(evalEngine, index, snapshotLoader,
                sessionWriter, traceWriter, dryRunTraceWriter, actionDispatchService, contextAssembler);
    }

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of());
    }

    /** EvalEngine 返回带 score 的结果，EvalServiceImpl 应原样透传。 */
    @Test
    void scorecard_result_score_isPropagated() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                1L, "fraud_check", "1", null, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("HIGH_RISK", 10)),
                null, "SCORECARD");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));

        Decision d = new Decision("HIGH_RISK", "", 10, 1L);
        EvalResult engineResult = new EvalResult(true, d, List.of(d), List.of(), null, List.of(), 60.0, null, null);
        when(evalEngine.evaluate(any())).thenReturn(engineResult);
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertEquals(60.0, result.score());
    }

    /** MISS 时 score 为 null，EvalServiceImpl 透传。 */
    @Test
    void scorecard_miss_score_isNull() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                1L, "fraud_check", "1", null, List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("HIGH_RISK", 10)),
                null, "SCORECARD");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(evalEngine.evaluate(any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertNull(result.score());
    }
}
