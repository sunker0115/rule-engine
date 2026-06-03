package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NodeTraceRowTest {

    @Test
    void 字段读写正确() {
        NodeTraceRow row = new NodeTraceRow();
        row.setId(2L);
        row.setEvaluationSessionId(10L);
        row.setTenantId(100L);
        row.setRuleVersionId(5L);
        row.setNodePath("0.1.2");
        row.setNodeType("CONDITION");
        row.setResult(Boolean.TRUE);

        assertThat(row.getId()).isEqualTo(2L);
        assertThat(row.getEvaluationSessionId()).isEqualTo(10L);
        assertThat(row.getTenantId()).isEqualTo(100L);
        assertThat(row.getRuleVersionId()).isEqualTo(5L);
        assertThat(row.getNodePath()).isEqualTo("0.1.2");
        assertThat(row.getNodeType()).isEqualTo("CONDITION");
        assertThat(row.getResult()).isTrue();
    }
}
