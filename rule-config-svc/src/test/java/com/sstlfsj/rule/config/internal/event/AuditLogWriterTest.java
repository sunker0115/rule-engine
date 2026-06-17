package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.internal.domain.ActorType;
import com.sstlfsj.rule.config.internal.domain.AuditAction;
import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.domain.AuditTargetType;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** 验证集中审计监听器把 typed 快照序列化为 JSON 落到 AuditLog 并 INSERT。 */
@ExtendWith(MockitoExtension.class)
class AuditLogWriterTest {

    @Mock AuditLogMapper auditLogMapper;
    AuditLogWriter writer;

    private AuditLogWriter writer() {
        if (writer == null) {
            writer = new AuditLogWriter(auditLogMapper, JsonMapper.builder().build());
        }
        return writer;
    }

    @Test
    void onOperationAudited_serializesTypedSnapshotsAndInserts() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 9, 10, 0);
        OperationAuditedEvent event = new OperationAuditedEvent(
                1L, "alice", ActorType.USER,AuditAction.CREATE, AuditTargetType.RULE_DEFINITION, "10",
                new DraftCreatedSnapshot(10L, 20L), new DraftCreatedSnapshot(10L, 20L), now);

        writer().onOperationAudited(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getTenantId()).isEqualTo(1L);
        assertThat(log.getActor()).isEqualTo("alice");
        assertThat(log.getActorType()).isEqualTo(ActorType.USER);
        assertThat(log.getAction()).isEqualTo(AuditAction.CREATE);
        assertThat(log.getTargetType()).isEqualTo(AuditTargetType.RULE_DEFINITION);
        assertThat(log.getTargetId()).isEqualTo("10");
        // 快照序列化为 JSON String：字段名/值与 record 对齐
        assertThat(log.getBeforeSnapshot()).contains("\"ruleDefinitionId\":10", "\"ruleVersionId\":20");
        assertThat(log.getAfterSnapshot()).contains("\"ruleDefinitionId\":10", "\"ruleVersionId\":20");
        assertThat(log.getOperatedAt()).isEqualTo(now);
    }

    @Test
    void onOperationAudited_serializesMetricSnapshot() {
        OperationAuditedEvent event = new OperationAuditedEvent(
                1L, "bob", ActorType.USER,AuditAction.UPDATE, AuditTargetType.METRIC_DEFINITION, "5",
                null, new MetricChangedSnapshot("amount", 3, true), LocalDateTime.now());

        writer().onOperationAudited(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getBeforeSnapshot()).isNull();
        assertThat(log.getAfterSnapshot()).contains("\"metricCode\":\"amount\"", "\"version\":3", "\"breaking\":true");
    }

    @Test
    void onOperationAudited_nullSnapshotsWriteNull() {
        OperationAuditedEvent event = new OperationAuditedEvent(
                1L, "carol", ActorType.USER,AuditAction.DISABLE, AuditTargetType.RULE_DEFINITION, "9",
                null, null, LocalDateTime.now());

        writer().onOperationAudited(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getBeforeSnapshot()).isNull();
        assertThat(log.getAfterSnapshot()).isNull();
    }

    @Test
    void onOperationAudited_capturesTraceIdFromMdc() {
        MDC.put("traceId", "trace-abc-123");
        try {
            OperationAuditedEvent event = new OperationAuditedEvent(
                    1L, "dave", ActorType.USER,AuditAction.PUBLISH, AuditTargetType.RULE_DEFINITION, "11",
                    null, new RulePublishedSnapshot(30L, 2L), LocalDateTime.now());

            writer().onOperationAudited(event);

            ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
            verify(auditLogMapper).insert(captor.capture());
            assertThat(captor.getValue().getTraceId()).isEqualTo("trace-abc-123");
        } finally {
            MDC.clear();
        }
    }

    @Test
    void onOperationAudited_traceIdNullWhenNoMdc() {
        MDC.remove("traceId");
        OperationAuditedEvent event = new OperationAuditedEvent(
                1L, "erin", ActorType.USER,AuditAction.DISABLE, AuditTargetType.RULE_DEFINITION, "12",
                null, null, LocalDateTime.now());

        writer().onOperationAudited(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        assertThat(captor.getValue().getTraceId()).isNull();
    }
}
