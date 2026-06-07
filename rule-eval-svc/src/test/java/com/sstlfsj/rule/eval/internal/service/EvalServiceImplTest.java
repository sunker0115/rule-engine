package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.async.EvaluationEventPublisher;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
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

/** doEvaluate 事件驱动后的单测：PULL 主路径发审计/action 事件，dry-run 路径仍同步写。 */
@ExtendWith(MockitoExtension.class)
class EvalServiceImplTest {

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
                "evt-001", Instant.now(), Map.of(), Map.of(),
                com.sstlfsj.rule.kernel.api.model.EventSource.HTTP);
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
    }

    @Test
    void evaluate_publishesAuditWithEngineContext() {
        EvalContext engineCtx = ctx();
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), engineCtx));

        impl.evaluate(event());

        // 复用引擎组装的上下文发审计事件（异步落 session 快照），不再同步 updateFinal
        verify(eventPublisher).publishAudit(anyLong(), any(), eq("PULL"), eq(1), any(), eq(engineCtx));
    }

    @Test
    void evaluate_noMatchingRules_returnsMiss_noEvents() {
        when(evalEngine.match(any(RuleEvent.class))).thenReturn(List.of());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        verifyNoInteractions(eventPublisher);
        verify(evalEngine, never()).evaluateWithContext(any(RuleEvent.class), anyList(), any(Instant.class));
    }

    @Test
    void evaluate_ruleHit_returnsHitWithDecision_publishesAudit() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertFalse(result.hitDecisions().isEmpty());
        assertEquals("REJECT", result.hitDecisions().get(0).code());
        verify(eventPublisher).publishAudit(anyLong(), any(), eq("PULL"), anyInt(), any(), any());
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
    void dryRun_writesToDryRunSessionNotProd_noEvents() {
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
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void dryRun_writesDryRunTraceWriter() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class)))
                .thenReturn(new EvalOutcome(EvalResult.miss(), ctx()));
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verify(dryRunTraceWriter).write(anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleHit_publishesActions() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(hitResult("REJECT", 10, 1L), ctx()));

        impl.evaluate(event());

        verify(eventPublisher).publishActions(anyLong(), eq(1L), anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleMiss_doesNotPublishActions() {
        stubPull(snapshot(1L, "REJECT"), new EvalOutcome(EvalResult.miss(), ctx()));

        impl.evaluate(event());

        verify(eventPublisher, never()).publishActions(anyLong(), anyLong(), anyString(), anyString(), anyList());
    }

    @Test
    void dryRun_doesNotPublishActions() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluateWithContext(any(RuleEvent.class), anyList(),
                any(SceneExecutionStrategy.class), any(Instant.class)))
                .thenReturn(new EvalOutcome(hitResult("PASS", 10, 42L), ctx()));
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verifyNoInteractions(eventPublisher);
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
