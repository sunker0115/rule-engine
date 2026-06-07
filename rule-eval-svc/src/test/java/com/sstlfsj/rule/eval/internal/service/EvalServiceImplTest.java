package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
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

@ExtendWith(MockitoExtension.class)
class EvalServiceImplTest {

    @Mock EvalEngine evalEngine;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;
    @Mock DryRunTraceWriter dryRunTraceWriter;
    @Mock ActionDispatchService actionDispatchService;

    EvalServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new EvalServiceImpl(evalEngine, snapshotLoader,
                sessionWriter, traceWriter, dryRunTraceWriter, actionDispatchService);
    }

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of(), com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
    }

    private RuleVersionSnapshot snapshot(Long id, String decisionCode) {
        return new RuleVersionSnapshot(
                id, "fraud_check", "1",
                new ConditionNode("EQ", null, null, Map.of(), 0.0),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding(decisionCode, 10)),
                null, null);
    }

    private EvalResult hitResult(String code, int priority, Long ruleVersionId) {
        Decision d = new Decision(code, "", priority, ruleVersionId);
        return new EvalResult(true, d, List.of(d), List.of(), null, List.of(), null, null, null);
    }

    private EvalContext ctx() {
        return new EvalContext("1", event(), null, Map.of(), Instant.parse("2024-01-01T00:00:00Z"));
    }

    /** PULL：stub match 返回候选 + evaluateWithContext 返回 outcome。 */
    private void stubPull(RuleVersionSnapshot snap, EvalOutcome outcome) {
        when(evalEngine.match(any(RuleEvent.class))).thenReturn(List.of(snap));
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class)))
                .thenReturn(outcome);
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);
    }

    @Test
    void evaluate_passesEngineContextToUpdateFinal() {
        EvalContext engineCtx = ctx();
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), engineCtx));

        impl.evaluate(event());

        // 复用引擎组装的上下文写快照，不再二次取数
        verify(sessionWriter).updateFinal(eq(1L), any(), eq(engineCtx));
    }

    @Test
    void evaluate_noMatchingRules_returnsMiss() {
        when(evalEngine.match(any(RuleEvent.class))).thenReturn(List.of());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        verifyNoInteractions(sessionWriter);
        verify(evalEngine, never()).evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class));
    }

    @Test
    void evaluate_ruleHit_returnsHitWithDecision() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertFalse(result.hitDecisions().isEmpty());
        assertEquals("REJECT", result.hitDecisions().get(0).code());
        verify(sessionWriter).updateFinal(anyLong(), any(), any());
        verify(traceWriter).write(anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleMiss_returnsMiss() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), ctx()));

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertTrue(result.hitDecisions().isEmpty());
        assertNull(result.finalDecision());
    }

    @Test
    void evaluate_multipleHits_highestPriorityWins() {
        // EvalEngine already picks highest priority; test just verifies propagation
        Decision highPriority = new Decision("REJECT", "", 20, 2L);
        EvalResult engineResult = new EvalResult(true, highPriority,
                List.of(new Decision("LOW_RISK", "", 5, 1L), highPriority),
                List.of(), null, List.of(), null, null, null);
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(engineResult, ctx()));

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertEquals("REJECT", result.finalDecision().code(),
                "priority=20 的 REJECT 应优先于 priority=5 的 LOW_RISK");
    }

    @Test
    void acceptEvent_returnsTrueAndDoesNotBlock() throws Exception {
        impl.afterPropertiesSet();
        try {
            boolean accepted = impl.acceptEvent(event());
            assertTrue(accepted);
        } finally {
            impl.destroy();
        }
    }

    @Test
    void dryRun_reusesEngineOutcome() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        EvalContext engineCtx = ctx();
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), engineCtx));
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verify(sessionWriter).updateDryRunFinal(eq(1L), any(), eq(engineCtx));
    }

    @Test
    void dryRun_writesToDryRunSessionNotProd() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), ctx()));
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        EvalResult result = impl.dryRun(event(), 42L);

        assertFalse(result.ruleHit());
        verify(sessionWriter).insertDryRunPending(any(), eq(42L));
        verify(sessionWriter, never()).insertPending(any(), anyInt(), anyString());
    }

    @Test
    void evaluate_ruleHit_callsProdTraceWriter_notDryRunWriter() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        impl.evaluate(event());

        verify(traceWriter).write(anyString(), anyString(), anyList());
        verifyNoInteractions(dryRunTraceWriter);
    }

    @Test
    void dryRun_writesDryRunTraceWriter_notProdWriter() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), ctx()));
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verify(dryRunTraceWriter).write(anyString(), anyString(), anyList());
        verifyNoInteractions(traceWriter);
    }

    @Test
    void evaluate_ruleHit_dispatchesAction() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        impl.evaluate(event());

        verify(actionDispatchService).dispatch(anyLong(), anyLong(), anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleMiss_doesNotDispatchAction() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), ctx()));

        impl.evaluate(event());

        verifyNoInteractions(actionDispatchService);
    }

    @Test
    void dryRun_doesNotDispatchAction() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class)))
                .thenReturn(new EvalOutcome(hitResult("PASS", 10, 42L), ctx()));
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verifyNoInteractions(actionDispatchService);
    }

    @Test
    void evaluate_scoreFromEngine_isPropagated() {
        Decision d = new Decision("REJECT", "", 10, 1L);
        EvalResult engineResult = new EvalResult(true, d, List.of(d), List.of(), null, List.of(), 60.0, null, null);
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(engineResult, ctx()));

        EvalResult result = impl.evaluate(event());

        assertEquals(60.0, result.score());
    }

    @Test
    void evaluate_scoreIsNull_forBooleanRules() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        EvalResult result = impl.evaluate(event());

        assertNull(result.score());
    }
}
