package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.async.AuditPersister;
import com.sstlfsj.rule.eval.internal.async.AuditRecorded;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证审计事件被消费后单次 INSERT 终态 session（不再 PENDING→UPDATE），并旁路写 trace。 */
class AuditPersisterTest {

    @Test
    void insertsTerminalSessionOnceAndWritesTrace() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        tools.jackson.databind.ObjectMapper om = tools.jackson.databind.json.JsonMapper.builder().build();
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, om);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        persister.onAudit(new AuditRecorded(42L, event, "PULL", 1, EvalResult.miss(), null, null));

        Thread.sleep(300);   // 等异步消费
        persister.destroy();

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(mapper, times(1)).insert(captor.capture());
        EvaluationSession s = captor.getValue();
        assertThat(s.getId()).isEqualTo(42L);
        assertThat(s.getStatus()).isEqualTo("MISS");
        assertThat(s.getTenantId()).isEqualTo(1L);
        assertThat(s.getMode()).isEqualTo("PULL");
        verify(traceWriter, times(1)).write(eq("1"), eq("42"), any());
    }

    @Test
    void blockedBy_nonNull_persistsBlockedStatusAndBlockedBy() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        tools.jackson.databind.ObjectMapper om = tools.jackson.databind.json.JsonMapper.builder().build();
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, om);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e2").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        // 候选被 Pre-Gate 全拦截：result 为 miss 但 blockedBy 非 null → 落 BLOCKED 而非 MISS
        persister.onAudit(new AuditRecorded(43L, event, "PULL", 1, EvalResult.miss(), null, "ROLLOUT"));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(mapper, times(1)).insert(captor.capture());
        EvaluationSession s = captor.getValue();
        assertThat(s.getStatus()).isEqualTo("BLOCKED");
        assertThat(s.getBlockedBy()).isEqualTo("ROLLOUT");
    }

    @Test
    void scorecardResult_persistsScore() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        tools.jackson.databind.ObjectMapper om = tools.jackson.databind.json.JsonMapper.builder().build();
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, om);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e3").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        // SCORECARD 命中：result.score 非 null → 落审计 score 列
        EvalResult scored = new EvalResult(true, null, java.util.List.of(), java.util.List.of(),
                null, java.util.List.of(), 87.5, null, null);
        persister.onAudit(new AuditRecorded(44L, event, "PULL", 1, scored, null, null));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(mapper, times(1)).insert(captor.capture());
        assertThat(captor.getValue().getScore()).isEqualTo(87.5);
    }

    @Test
    void hitDecisions_serializedAsObjectsWithCategory_andSessionCategoryFromFinal() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        tools.jackson.databind.ObjectMapper om = tools.jackson.databind.json.JsonMapper.builder().build();
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, om);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e9").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        Decision dev = new Decision("REVIEW", "", 20, 11L, "中危");
        Decision amt = new Decision("REVIEW", "", 10, 22L, "大额");
        EvalResult r = new EvalResult(true, dev, java.util.List.of(dev, amt), java.util.List.of(),
                null, java.util.List.of(), null, "中危", null);
        persister.onAudit(new AuditRecorded(91L, event, "PULL", 2, r, null, null));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<EvaluationSession> captor = ArgumentCaptor.forClass(EvaluationSession.class);
        verify(mapper, times(1)).insert(captor.capture());
        EvaluationSession s = captor.getValue();
        assertThat(s.getCategory()).isEqualTo("中危");
        assertThat(s.getHitDecisions()).contains("\"category\":\"中危\"")
                .contains("\"category\":\"大额\"").contains("\"ruleVersionId\":11");
    }
}
