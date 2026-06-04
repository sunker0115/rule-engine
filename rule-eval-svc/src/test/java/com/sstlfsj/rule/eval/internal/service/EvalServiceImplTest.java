package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.action.ActionDispatchService;
import com.sstlfsj.rule.kernel.internal.index.SceneRuleIndex;
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
    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;
    @Mock DryRunTraceWriter dryRunTraceWriter;
    @Mock ActionDispatchService actionDispatchService;

    EvalServiceImpl impl;

    @BeforeEach
    void setUp() {
        impl = new EvalServiceImpl(evalEngine, index, snapshotLoader,
                sessionWriter, traceWriter, dryRunTraceWriter, actionDispatchService);
    }

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of());
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

    @Test
    void evaluate_noMatchingRules_returnsMiss() {
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        verifyNoInteractions(sessionWriter);
        verifyNoInteractions(evalEngine);
    }

    @Test
    void evaluate_ruleHit_returnsHitWithDecision() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(evalEngine.evaluate(any())).thenReturn(hitResult("REJECT", 10, 1L));
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertTrue(result.ruleHit());
        assertFalse(result.hitDecisions().isEmpty());
        assertEquals("REJECT", result.hitDecisions().get(0).code());
        verify(sessionWriter).updateFinal(anyLong(), any());
        verify(traceWriter).write(anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleMiss_returnsMiss() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(evalEngine.evaluate(any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertTrue(result.hitDecisions().isEmpty());
        assertNull(result.finalDecision());
    }

    @Test
    void evaluate_multipleHits_highestPriorityWins() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        // EvalEngine already picks highest priority; test just verifies propagation
        Decision highPriority = new Decision("REJECT", "", 20, 2L);
        EvalResult engineResult = new EvalResult(true, highPriority,
                List.of(new Decision("LOW_RISK", "", 5, 1L), highPriority),
                List.of(), null, List.of(), null, null, null);
        when(evalEngine.evaluate(any())).thenReturn(engineResult);
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

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
    void dryRun_writesToDryRunSessionNotProd() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluate(any(), anyList())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        EvalResult result = impl.dryRun(event(), 42L);

        assertFalse(result.ruleHit());
        verify(sessionWriter).insertDryRunPending(any(), eq(42L));
        verify(sessionWriter, never()).insertPending(any(), anyInt(), anyString());
    }

    @Test
    void evaluate_ruleHit_callsProdTraceWriter_notDryRunWriter() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(evalEngine.evaluate(any())).thenReturn(hitResult("REJECT", 10, 1L));
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        impl.evaluate(event());

        verify(traceWriter).write(anyString(), anyString(), anyList());
        verifyNoInteractions(dryRunTraceWriter);
    }

    @Test
    void dryRun_writesDryRunTraceWriter_notProdWriter() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluate(any(), anyList())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verify(dryRunTraceWriter).write(anyString(), anyString(), anyList());
        verifyNoInteractions(traceWriter);
    }

    @Test
    void evaluate_ruleHit_dispatchesAction() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(evalEngine.evaluate(any())).thenReturn(hitResult("REJECT", 10, 1L));
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        impl.evaluate(event());

        verify(actionDispatchService).dispatch(anyLong(), anyLong(), anyString(), anyString(), anyList());
    }

    @Test
    void evaluate_ruleMiss_doesNotDispatchAction() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(evalEngine.evaluate(any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        impl.evaluate(event());

        verifyNoInteractions(actionDispatchService);
    }

    @Test
    void dryRun_doesNotDispatchAction() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(evalEngine.evaluate(any(), anyList())).thenReturn(hitResult("PASS", 10, 42L));
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        impl.dryRun(event(), 42L);

        verifyNoInteractions(actionDispatchService);
    }

    @Test
    void evaluate_scoreFromEngine_isPropagated() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        Decision d = new Decision("REJECT", "", 10, 1L);
        EvalResult engineResult = new EvalResult(true, d, List.of(d), List.of(), null, List.of(), 60.0, null, null);
        when(evalEngine.evaluate(any())).thenReturn(engineResult);
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertEquals(60.0, result.score());
    }

    @Test
    void evaluate_scoreIsNull_forBooleanRules() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(evalEngine.evaluate(any())).thenReturn(hitResult("REJECT", 10, 1L));
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertNull(result.score());
    }
}
