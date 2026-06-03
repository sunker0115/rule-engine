package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.context.EvalContextAssembler;
import com.sstlfsj.rule.eval.internal.index.SceneRuleIndex;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.*;
import com.sstlfsj.rule.kernel.api.model.ast.ConditionNode;
import com.sstlfsj.rule.kernel.api.spi.executor.RuleVersionExecutor;
import com.sstlfsj.rule.kernel.api.spi.pregate.PreGate;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @Mock SceneRuleIndex index;
    @Mock SceneSnapshotLoader snapshotLoader;
    @Mock EvalContextAssembler contextAssembler;
    @Mock RuleVersionExecutor executor;
    @Mock EvalSessionWriter sessionWriter;
    @Mock TraceWriter traceWriter;

    // EvalServiceImpl 构造器接受 List<PreGate>，Mockito @InjectMocks 会注入空列表
    @InjectMocks EvalServiceImpl impl;

    private RuleEvent event() {
        return new RuleEvent("1", "fraud_check", "RISK_EVENT", "u1",
                "evt-001", Instant.now(), Map.of(), Map.of());
    }

    private RuleVersionSnapshot snapshot(Long id, String decisionCode) {
        return new RuleVersionSnapshot(
                id, "fraud_check", "1",
                new ConditionNode("EQ", null, null, Map.of()),
                List.of(),
                List.of(new RuleVersionSnapshot.DecisionBinding(decisionCode, 10)));
    }

    @Test
    void evaluate_noMatchingRules_returnsMiss() {
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of());

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        verifyNoInteractions(sessionWriter);
    }

    @Test
    void evaluate_ruleHit_returnsHitWithDecision() {
        RuleVersionSnapshot snap = snapshot(1L, "REJECT");
        when(index.match("1", "fraud_check", "RISK_EVENT")).thenReturn(List.of(snap));
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.hit());
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
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertPending(any(), anyInt(), anyString())).thenReturn(1L);

        EvalResult result = impl.evaluate(event());

        assertFalse(result.ruleHit());
        assertTrue(result.hitDecisions().isEmpty());
        assertNull(result.finalDecision());
    }

    @Test
    void acceptEvent_returnsTrueAndDoesNotBlock() {
        // acceptEvent 异步投递，不阻塞调用方，直接返回 true
        boolean accepted = impl.acceptEvent(event());

        assertTrue(accepted);
    }

    @Test
    void dryRun_writesToDryRunSessionNotProd() {
        RuleVersionSnapshot snap = snapshot(42L, "PASS");
        when(snapshotLoader.loadById(42L)).thenReturn(snap);
        when(contextAssembler.assemble(any(), any()))
                .thenReturn(new EvalContext("1", event(), new Subject("u1", SubjectType.USER, Map.of()), Map.of()));
        when(executor.execute(any(), any())).thenReturn(EvalResult.miss());
        when(sessionWriter.insertDryRunPending(any(), anyLong())).thenReturn(1L);

        EvalResult result = impl.dryRun(event(), 42L);

        assertFalse(result.ruleHit());
        verify(sessionWriter).insertDryRunPending(any(), eq(42L));
        verify(sessionWriter, never()).insertPending(any(), anyInt(), anyString());
    }
}
