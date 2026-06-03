package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

class EvalSessionRowTest {

    @Test
    void 字段读写正确() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 3, 10, 0);
        EvalSessionRow row = new EvalSessionRow();
        row.setId(1L);
        row.setTenantId(100L);
        row.setEventId("evt-abc");
        row.setSceneCode("risk.transfer");
        row.setStatus("HIT");
        row.setStartedAt(start);

        assertThat(row.getId()).isEqualTo(1L);
        assertThat(row.getTenantId()).isEqualTo(100L);
        assertThat(row.getEventId()).isEqualTo("evt-abc");
        assertThat(row.getSceneCode()).isEqualTo("risk.transfer");
        assertThat(row.getStatus()).isEqualTo("HIT");
        assertThat(row.getStartedAt()).isEqualTo(start);
    }
}
