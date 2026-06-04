package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ScorecardRootNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import com.sstlfsj.rule.kernel.internal.evaluator.ScorecardExecutor;
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

/** 验证 EvalServiceImpl 按 kind 字段路由到正确 executor（D12）。 */
@ExtendWith(MockitoExtension.class)
class EvalServiceImplScorecardTest {

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalContextAssembler contextAssembler;
    @Mock RuleVersionExecutor executor;
    @Mock ScorecardExecutor scorecardExecutor;
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;
    @Mock DryRunTraceWriter dryRunTraceWriter;
    @Mock ActionDispatchService actionDispatchService;

    // 显式构造，避免 Mockito 对两个 RuleVersionExecutor 类型 mock 的注入歧义
    EvalServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new EvalServiceImpl(index, snapshotLoader, List.of(), contextAssembler,
                executor, scorecardExecutor, sessionWriter, traceWriter, dryRunTraceWriter,
                actionDispatchService);
    }

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of());
    }

    private EvalContext evalContext() {
        return new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of());
    }

    /** kind=SCORECARD 的快照应路由到 scorecardExecutor，score 透传到最终结果。 */
    @Test
    void scorecard_snapshot_routes_to_scorecardExecutor_and_returns_score() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                1L, "fraud_check", "1",
                new ScorecardRootNode(List.of(), 50.0),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("HIGH_RISK", 10)),
                null,
                "SCORECARD");

        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any())).thenReturn(evalContext());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);
        when(scorecardExecutor.execute(any(), any()))
                .thenReturn(new EvalResult(true, null, List.of(), List.of(), null, List.of(), 60.0));

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertEquals(60.0, result.score());
        verify(scorecardExecutor).execute(eq(snap), any());
        verify(executor, never()).execute(any(), any());
    }

    /** kind=AST_BOOLEAN（默认）的快照应路由到 executor，不走 scorecardExecutor。 */
    @Test
    void astBoolean_snapshot_routes_to_default_executor() {
        RuleVersionSnapshot snap = new RuleVersionSnapshot(
                2L, "fraud_check", "1",
                null,
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding("REJECT", 10)),
                null,
                "AST_BOOLEAN");

        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any())).thenReturn(evalContext());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);
        when(executor.execute(any(), any())).thenReturn(EvalResult.hit());

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        verify(executor).execute(eq(snap), any());
        verify(scorecardExecutor, never()).execute(any(), any());
    }
}
