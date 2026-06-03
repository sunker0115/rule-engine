package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class AuditLogRowTest {

    @Test
    void 字段读写正确() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 3, 10, 0);
        AuditLogRow row = new AuditLogRow();
        row.setId(3L);
        row.setTenantId(100L);
        row.setActor("user-42");
        row.setActorType("USER");
        row.setAction("PUBLISH");
        row.setTargetType("RULE");
        row.setTargetId("rule-99");
        row.setOperatedAt(now);

        assertThat(row.getId()).isEqualTo(3L);
        assertThat(row.getTenantId()).isEqualTo(100L);
        assertThat(row.getActor()).isEqualTo("user-42");
        assertThat(row.getActorType()).isEqualTo("USER");
        assertThat(row.getAction()).isEqualTo("PUBLISH");
        assertThat(row.getTargetType()).isEqualTo("RULE");
        assertThat(row.getTargetId()).isEqualTo("rule-99");
        assertThat(row.getOperatedAt()).isEqualTo(now);
    }
}
