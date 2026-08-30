package com.sstlfsj.rule.eval.async;

import com.sstlfsj.rule.eval.internal.async.AuditPersister;
import com.sstlfsj.rule.eval.internal.async.AuditRecordedEvent;
import com.sstlfsj.rule.eval.internal.domain.EvalMode;
import com.sstlfsj.rule.eval.internal.domain.EvaluationSession;
import com.sstlfsj.rule.eval.internal.domain.SessionStatus;
import com.sstlfsj.rule.eval.internal.repository.EvaluationSessionMapper;
import com.sstlfsj.rule.eval.internal.repository.BestEffortJsonTypeHandler;
import com.sstlfsj.rule.kernel.api.model.Decision;
import com.sstlfsj.rule.kernel.api.model.EvalContext;
import com.sstlfsj.rule.kernel.api.model.EvalResult;
import com.sstlfsj.rule.kernel.api.model.EventSource;
import com.sstlfsj.rule.kernel.api.model.RuleEvent;
import com.sstlfsj.rule.kernel.api.spi.trace.TraceWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.sql.PreparedStatement;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证审计事件被消费后多行批量 INSERT 终态 session（不再 PENDING→UPDATE），并旁路写 trace。 */
class AuditPersisterTest {

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<EvaluationSession>> batchCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    @Test
    void insertsTerminalSessionOnceAndWritesTrace() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e1").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        persister.onAudit(new AuditRecordedEvent(42L, event, EvalMode.PULL,1, EvalResult.miss(), null, null, 0, List.of()));

        Thread.sleep(300);   // 等异步消费
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        assertThat(s.getId()).isEqualTo(42L);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.MISS);
        assertThat(s.getTenantId()).isEqualTo(1L);
        assertThat(s.getMode()).isEqualTo(EvalMode.PULL);
        verify(traceWriter, times(1)).write(eq("1"), eq("42"), any());
    }

    @Test
    void startedAtFromContextNow_andEvalDurationMsFromEvent() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e-dur").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        // 固定 evalNow：started_at 必须取 context.now()（真实评估起点），非落库时刻
        Instant evalNow = Instant.parse("2026-06-09T01:02:03Z");
        EvalContext ctx = new EvalContext("1", event, null, Map.of(), evalNow);
        persister.onAudit(new AuditRecordedEvent(45L, event, EvalMode.PULL,1, EvalResult.miss(), ctx, null, 42, List.of()));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        LocalDateTime expectedStart = LocalDateTime.ofInstant(evalNow, ZoneId.systemDefault());
        assertThat(s.getStartedAt()).isEqualTo(expectedStart);
        assertThat(s.getEvalDurationMs()).isEqualTo(42);
        assertThat(s.getFinishedAt()).isEqualTo(expectedStart.plusNanos(42L * 1_000_000L));
    }

    @Test
    void blockedBy_nonNull_persistsBlockedStatusAndBlockedBy() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e2").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        // 候选被 Pre-Gate 全拦截：result 为 miss 但 blockedBy 非 null → 落 BLOCKED 而非 MISS
        persister.onAudit(new AuditRecordedEvent(43L, event, EvalMode.PULL,1, EvalResult.miss(), null, "ROLLOUT", 0, List.of()));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        assertThat(s.getStatus()).isEqualTo(SessionStatus.BLOCKED);
        assertThat(s.getBlockedBy()).isEqualTo("ROLLOUT");
    }

    @Test
    void scorecardResult_persistsScore() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e3").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        // SCORECARD 命中：result.score 非 null → 落审计 score 列
        EvalResult scored = new EvalResult(true, null, java.util.List.of(), java.util.List.of(),
                null, 87.5, null, null);
        persister.onAudit(new AuditRecordedEvent(44L, event, EvalMode.PULL,1, scored, null, null, 0, List.of()));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getScore()).isEqualTo(87.5);
    }

    @Test
    void hitDecisions_serializedAsObjectsWithCategory_andSessionCategoryFromFinal() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e9").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        Decision dev = new Decision("REVIEW", "", 20, 11L, "中危");
        Decision amt = new Decision("REVIEW", "", 10, 22L, "大额");
        EvalResult r = new EvalResult(true, dev, java.util.List.of(dev, amt), java.util.List.of(),
                null, null, "中危", null);
        persister.onAudit(new AuditRecordedEvent(91L, event, EvalMode.PULL,2, r, null, null, 0, List.of(11L, 22L)));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        assertThat(s.getCategory()).isEqualTo("中危");
        assertThat(s.getHitDecisions()).extracting("category").containsExactly("中危", "大额");
        assertThat(s.getHitDecisions()).extracting("ruleVersionId").containsExactly(11L, 22L);
    }

    @Test
    void contextSnapshotEnabled_backfillsMetricValues() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        // 开关 ON：终态 session 回填 context_snapshot
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, true);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e-snap-on").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        EvalContext ctx = new EvalContext("1", event, null,
                Map.of("amount", new com.sstlfsj.rule.kernel.api.model.MetricValue(8888, "NUMBER", "PROVIDED")),
                Instant.parse("2026-06-09T01:02:03Z"));
        persister.onAudit(new AuditRecordedEvent(46L, event, EvalMode.PULL,1, EvalResult.miss(), ctx, null, 0, List.of(101L, 202L)));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        assertThat(s.getContextSnapshot().metrics()).containsEntry("amount", 8888);
        assertThat(s.getContextSnapshot().evalNow()).isEqualTo(Instant.parse("2026-06-09T01:02:03Z"));
    }

    @Test
    void contextSnapshotDisabled_leavesSnapshotNull() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        // 开关 OFF（默认）：即便事件携带 context 也不回填，快照保持 null
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e-snap-off").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        EvalContext ctx = new EvalContext("1", event, null,
                Map.of("amount", new com.sstlfsj.rule.kernel.api.model.MetricValue(8888, "NUMBER", "PROVIDED")),
                Instant.parse("2026-06-09T01:02:03Z"));
        persister.onAudit(new AuditRecordedEvent(47L, event, EvalMode.PULL,1, EvalResult.miss(), ctx, null, 0, List.of()));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        assertThat(captor.getValue().get(0).getContextSnapshot()).isNull();
    }

    @Test
    void captureEnabled_writesPayloadAndCandidateVersionIds() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        // 捕获开关 ON：重放三件套(payload + 候选版本 id + context_snapshot)一并落库
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, true);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e-replay").source(EventSource.HTTP)
                .occurredAt(Instant.now()).payload(Map.of("amount", 5000)).build();
        EvalContext ctx = new EvalContext("1", event, null, Map.of(), Instant.parse("2026-06-09T01:02:03Z"));
        persister.onAudit(new AuditRecordedEvent(48L, event, EvalMode.PULL, 2, EvalResult.miss(),
                ctx, null, 0, List.of(11L, 22L)));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        assertThat(s.getPayload()).containsEntry("amount", 5000);
        assertThat(s.getCandidateRuleVersionIds()).containsExactly(11L, 22L);
    }

    @Test
    void captureDisabled_leavesReplayColumnsNull() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e-replay-off").source(EventSource.HTTP)
                .occurredAt(Instant.now()).payload(Map.of("amount", 5000)).build();
        persister.onAudit(new AuditRecordedEvent(49L, event, EvalMode.PULL, 2, EvalResult.miss(),
                null, null, 0, List.of(11L, 22L)));

        Thread.sleep(300);
        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        assertThat(s.getPayload()).isNull();
        assertThat(s.getCandidateRuleVersionIds()).isNull();
    }

    @Test
    void typedHitDecisions_stillCreatesSessionBeforeJdbcBinding() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        // 类型化后 JSON 编码在 JDBC 绑定阶段进行；这里仍须先形成完整 session，避免消费线程丢整批。
        AuditPersister persister = new AuditPersister(2000, 200, 50, mapper, traceWriter, false);
        persister.afterPropertiesSet();

        RuleEvent event = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                .subjectId("u1").eventId("e-bad").source(EventSource.HTTP).occurredAt(Instant.now()).build();
        Decision d = new Decision("REJECT", "", 10, 1L);
        EvalResult r = new EvalResult(true, d, List.of(d), List.of(), null, null, null, null);
        persister.onAudit(new AuditRecordedEvent(50L, event, EvalMode.PULL, 1, r, null, null, 0, List.of()));

        Thread.sleep(300);
        persister.destroy();

        // session 已进入批量 INSERT；实际 JSON 编码失败由 TypeHandler 单字段降级。
        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, times(1)).insertBatch(captor.capture());
        EvaluationSession s = captor.getValue().get(0);
        assertThat(s.getId()).isEqualTo(50L);
        assertThat(s.getHitDecisions()).hasSize(1);
    }

    @Test
    void bestEffortJsonHandler_serializationFails_writesSqlNull() throws Exception {
        PreparedStatement statement = mock(PreparedStatement.class);
        BestEffortJsonTypeHandler handler = new BestEffortJsonTypeHandler(Object.class) {
            @Override
            public String toJson(Object value) {
                throw new RuntimeException("serialize boom");
            }
        };

        handler.setNonNullParameter(statement, 3, Map.of("bad", true), null);

        verify(statement).setNull(3, Types.VARCHAR);
    }

    @Test
    void destroy_drainsEntireQueue_notJustOneBatch() throws Exception {
        EvaluationSessionMapper mapper = mock(EvaluationSessionMapper.class);
        TraceWriter traceWriter = mock(TraceWriter.class);
        // batchSize=2、flushInterval 很长（不靠定时 flush）：积压 5 条 > batchSize，destroy 必须排空全部（#4）
        AuditPersister persister = new AuditPersister(2000, 2, 100000, mapper, traceWriter, false);
        persister.afterPropertiesSet();
        for (int i = 0; i < 5; i++) {
            RuleEvent ev = RuleEvent.builder().tenantId("1").sceneCode("s").eventType("t")
                    .subjectId("u").eventId("e" + i).source(EventSource.HTTP).occurredAt(Instant.now()).build();
            persister.onAudit(new AuditRecordedEvent(100L + i, ev, EvalMode.PULL, 1, EvalResult.miss(), null, null, 0, List.of()));
        }

        persister.destroy();

        ArgumentCaptor<List<EvaluationSession>> captor = batchCaptor();
        verify(mapper, atLeastOnce()).insertBatch(captor.capture());
        int total = captor.getAllValues().stream().mapToInt(List::size).sum();
        assertThat(total).isEqualTo(5);   // 全部落库，停机不丢积压
    }
}
