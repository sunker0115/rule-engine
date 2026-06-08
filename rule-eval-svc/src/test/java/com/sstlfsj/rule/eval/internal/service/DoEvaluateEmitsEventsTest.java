package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.async.EvaluationEventPublisher;
import com.sstlfsj.rule.eval.internal.session.EvalSessionWriter;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.spi.trace.DryRunTraceWriter;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 doEvaluate 改事件驱动：命中发审计+action 事件；有候选未命中只发审计；无候选不发事件；均不再同步写库。 */
class DoEvaluateEmitsEventsTest {

    private RuleEvent event(String eventId) {
        return RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId(eventId).source(EventSource.HTTP)
                .occurredAt(Instant.now()).build();
    }

    private EvalServiceImpl service(EvalEngine engine, EvaluationEventPublisher publisher) {
        return new EvalServiceImpl(engine, mock(SceneSnapshotLoader.class),
                mock(EvalSessionWriter.class), mock(DryRunTraceWriter.class), publisher);
    }

    @Test
    void hitEvaluation_publishesAuditAndActions() {
        EvalEngine engine = mock(EvalEngine.class);
        EvaluationEventPublisher publisher = mock(EvaluationEventPublisher.class);
        RuleEvent event = event("e1");
        when(engine.match(event)).thenReturn(List.of(mock(RuleVersionSnapshot.class)));
        Decision pass = new Decision("PASS", "", 1, 3L);
        EvalResult hit = new EvalResult(true, pass, List.of(pass), List.of(),
                null, List.of(), null, null, null);
        when(engine.evaluateWithContext(eq(event), anyList(), any()))
                .thenReturn(new EvalOutcome(hit, null));

        EvalResult result = service(engine, publisher).evaluate(event);

        assertThat(result.ruleHit()).isTrue();
        verify(publisher).publishAudit(anyLong(), eq(event), eq("PULL"), eq(1), eq(hit), any());
        verify(publisher).publishActions(anyLong(), eq(1L), eq("e1"), eq("s"), eq(List.of(pass)));
    }

    @Test
    void evaluatedMiss_withCandidates_publishesAuditOnly_noActions() {
        // 有候选但全未命中：审计无条件发(落 status=MISS)，action 受 ruleHit 门控不发
        EvalEngine engine = mock(EvalEngine.class);
        EvaluationEventPublisher publisher = mock(EvaluationEventPublisher.class);
        RuleEvent event = event("e3");
        when(engine.match(event)).thenReturn(List.of(mock(RuleVersionSnapshot.class)));
        EvalResult miss = new EvalResult(false, null, List.of(), List.of(),
                null, List.of(), null, null, null);
        when(engine.evaluateWithContext(eq(event), anyList(), any()))
                .thenReturn(new EvalOutcome(miss, null));

        EvalResult result = service(engine, publisher).evaluate(event);

        assertThat(result.ruleHit()).isFalse();
        verify(publisher).publishAudit(anyLong(), eq(event), eq("PULL"), eq(1), eq(miss), any());
        verify(publisher, never())
                .publishActions(anyLong(), anyLong(), anyString(), anyString(), anyList());
    }

    @Test
    void noCandidates_returnsMiss_noEvents() {
        EvalEngine engine = mock(EvalEngine.class);
        EvaluationEventPublisher publisher = mock(EvaluationEventPublisher.class);
        RuleEvent event = event("e2");
        when(engine.match(event)).thenReturn(List.of());

        EvalResult result = service(engine, publisher).evaluate(event);

        assertThat(result.ruleHit()).isFalse();
        verifyNoInteractions(publisher);
    }
}
