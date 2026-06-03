package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EvalSessionRowTest {

    @Test
    void 字段读写正确() {
        EvalSessionRow row = new EvalSessionRow();
        row.setId(1L);
        row.setTenantId(100L);
        row.setSceneCode("risk.transfer");
        row.setStatus("HIT");

        assertThat(row.getId()).isEqualTo(1L);
        assertThat(row.getTenantId()).isEqualTo(100L);
        assertThat(row.getSceneCode()).isEqualTo("risk.transfer");
        assertThat(row.getStatus()).isEqualTo("HIT");
    }
}
