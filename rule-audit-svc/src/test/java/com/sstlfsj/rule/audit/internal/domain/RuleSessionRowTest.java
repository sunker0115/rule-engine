package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RuleSessionRowTest {

    @Test
    void 字段读写正确() {
        LocalDateTime start = LocalDateTime.of(2026, 6, 5, 10, 0);
        RuleSessionRow row = new RuleSessionRow();
        row.setId(1L);
        row.setEventId("evt-abc");
        row.setSubjectId("u1");
        row.setStatus("HIT");
        row.setFinalDecision("REJECT");
        row.setEvalDurationMs(42);
        row.setStartedAt(start);
        row.setRuleVersionId(99L);

        assertThat(row.getId()).isEqualTo(1L);
        assertThat(row.getEventId()).isEqualTo("evt-abc");
        assertThat(row.getSubjectId()).isEqualTo("u1");
        assertThat(row.getStatus()).isEqualTo("HIT");
        assertThat(row.getFinalDecision()).isEqualTo("REJECT");
        assertThat(row.getEvalDurationMs()).isEqualTo(42);
        assertThat(row.getStartedAt()).isEqualTo(start);
        assertThat(row.getRuleVersionId()).isEqualTo(99L);
    }

    @Test
    void 默认值为null() {
        RuleSessionRow row = new RuleSessionRow();
        assertThat(row.getId()).isNull();
        assertThat(row.getEventId()).isNull();
        assertThat(row.getRuleVersionId()).isNull();
    }
}
