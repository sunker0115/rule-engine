package com.sstlfsj.rule.eval.internal.service;

import com.sstlfsj.rule.eval.internal.async.AuditRecordedEvent;
import com.sstlfsj.rule.eval.internal.event.DomainEventPublisher;
import com.sstlfsj.rule.eval.internal.snapshot.SceneSnapshotLoader;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalOutcome;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.internal.engine.EvalEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证 doEvaluate 改事件驱动：命中发审计事件；有候选未命中只发审计；无候选不发事件；均经 DomainEventPublisher。 */
class DoEvaluateEmitsEventsTest {

    private RuleEvent event(String eventId) {
        return RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId(eventId).source(EventSource.HTTP)
                .occurredAt(Instant.now()).build();
    }

    private EvalServiceImpl service(EvalEngine engine, DomainEventPublisher publisher) {
        return new EvalServiceImpl(engine, mock(SceneSnapshotLoader.class), publisher,
                mock(com.sstlfsj.rule.eval.internal.repository.RuleVersionReadMapper.class));
    }

    @Test
    void hitEvaluation_publishesAudit() {
        EvalEngine engine = mock(EvalEngine.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        RuleEvent event = event("e1");
        when(engine.match(event)).thenReturn(List.of(mock(RuleVersionSnapshot.class)));
        Decision pass = new Decision("PASS", "", 1, 3L);
        EvalResult hit = new EvalResult(true, pass, List.of(pass), List.of(),
                null, List.of(), null, null, null);
        when(engine.evaluateWithContext(eq(event), anyList(), any()))
                .thenReturn(new EvalOutcome(hit, null));

        EvalResult result = service(engine, publisher).evaluate(event);

        assertThat(result.ruleHit()).isTrue();
        verify(publisher).publish(argThat(o ->
                o instanceof AuditRecordedEvent a
                        && a.mode() == com.sstlfsj.rule.eval.internal.domain.EvalMode.PULL
                        && a.candidateCount() == 1
                        && a.result() == hit
                        && a.blockedBy() == null));
    }

    @Test
    void evaluatedMiss_withCandidates_publishesAuditOnly() {
        // 有候选但全未命中：审计无条件发(落 status=MISS)
        EvalEngine engine = mock(EvalEngine.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        RuleEvent event = event("e3");
        when(engine.match(event)).thenReturn(List.of(mock(RuleVersionSnapshot.class)));
        EvalResult miss = new EvalResult(false, null, List.of(), List.of(),
                null, List.of(), null, null, null);
        when(engine.evaluateWithContext(eq(event), anyList(), any()))
                .thenReturn(new EvalOutcome(miss, null));

        EvalResult result = service(engine, publisher).evaluate(event);

        assertThat(result.ruleHit()).isFalse();
        verify(publisher).publish(any(AuditRecordedEvent.class));
    }

    @Test
    void allCandidatesPreGateBlocked_publishesAuditWithBlockedBy() {
        // 候选被 Pre-Gate 全拦截：审计带 blockedBy(落 status=BLOCKED)
        EvalEngine engine = mock(EvalEngine.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        RuleEvent event = event("e4");
        when(engine.match(event)).thenReturn(List.of(mock(RuleVersionSnapshot.class)));
        EvalResult miss = new EvalResult(false, null, List.of(), List.of(),
                null, List.of(), null, null, null);
        when(engine.evaluateWithContext(eq(event), anyList(), any()))
                .thenReturn(new EvalOutcome(miss, null, "ROLLOUT"));

        EvalResult result = service(engine, publisher).evaluate(event);

        assertThat(result.ruleHit()).isFalse();
        verify(publisher).publish(argThat(o ->
                o instanceof AuditRecordedEvent a && "ROLLOUT".equals(a.blockedBy())));
    }

    @Test
    void noCandidates_returnsMiss_noEvents() {
        EvalEngine engine = mock(EvalEngine.class);
        DomainEventPublisher publisher = mock(DomainEventPublisher.class);
        RuleEvent event = event("e2");
        when(engine.match(event)).thenReturn(List.of());

        EvalResult result = service(engine, publisher).evaluate(event);

        assertThat(result.ruleHit()).isFalse();
        verifyNoInteractions(publisher);
    }
}
