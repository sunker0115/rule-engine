package com.sstlfsj.rule.config.internal.event;

import com.sstlfsj.rule.config.internal.domain.AuditLog;
import com.sstlfsj.rule.config.internal.repository.AuditLogMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/** 验证集中审计监听器把 OperationAuditedEvent 字段如实落到 AuditLog 并 INSERT。 */
@ExtendWith(MockitoExtension.class)
class AuditLogWriterTest {

    @Mock AuditLogMapper auditLogMapper;
    @InjectMocks AuditLogWriter writer;

    @Test
    void onOperationAudited_mapsAllFieldsAndInserts() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 9, 10, 0);
        OperationAuditedEvent event = new OperationAuditedEvent(
                1L, "alice", "USER", "PUBLISH", "rule_definition", "10",
                "{\"before\":1}", "{\"after\":2}", now);

        writer.onOperationAudited(event);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogMapper).insert(captor.capture());
        AuditLog log = captor.getValue();
        assertThat(log.getTenantId()).isEqualTo(1L);
        assertThat(log.getActor()).isEqualTo("alice");
        assertThat(log.getActorType()).isEqualTo("USER");
        assertThat(log.getAction()).isEqualTo("PUBLISH");
        assertThat(log.getTargetType()).isEqualTo("rule_definition");
        assertThat(log.getTargetId()).isEqualTo("10");
        assertThat(log.getBeforeSnapshot()).isEqualTo("{\"before\":1}");
        assertThat(log.getAfterSnapshot()).isEqualTo("{\"after\":2}");
        assertThat(log.getOperatedAt()).isEqualTo(now);
    }
}
