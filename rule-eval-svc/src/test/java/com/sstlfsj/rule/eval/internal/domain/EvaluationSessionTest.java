package com.sstlfsj.rule.eval.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationSessionTest {

    @Test
    void settersAndGetters_roundTrip() {
        EvaluationSession s = new EvaluationSession();
        s.setTenantId(1L);
        s.setEventId("evt-001");
        s.setSceneCode("fraud_check");
        s.setStatus("PENDING");
        s.setOccurredAt(LocalDateTime.now());

        assertEquals(1L, s.getTenantId());
        assertEquals("evt-001", s.getEventId());
        assertEquals("fraud_check", s.getSceneCode());
        assertEquals("PENDING", s.getStatus());
        assertNotNull(s.getOccurredAt());
    }

    @Test
    void contextSnapshot_setAndGet() {
        EvaluationSession s = new EvaluationSession();
        s.setContextSnapshot("{\"metrics\":{\"user.age\":25},\"evalNow\":\"2024-01-01T00:00:00Z\"}");
        assertEquals("{\"metrics\":{\"user.age\":25},\"evalNow\":\"2024-01-01T00:00:00Z\"}", s.getContextSnapshot());
    }

    @Test
    void score_setAndGet() {
        EvaluationSession s = new EvaluationSession();
        assertNull(s.getScore());          // 默认 null（AST_BOOLEAN 等无分场景）
        s.setScore(87.5);
        assertEquals(87.5, s.getScore());
    }

    @Test
    void contextSnapshot_defaultsToNull() {
        EvaluationSession s = new EvaluationSession();
        assertNull(s.getContextSnapshot());
    }

    @Test
    void contextSnapshot_setNullAllowed() {
        EvaluationSession s = new EvaluationSession();
        s.setContextSnapshot("{\"k\":1}");
        s.setContextSnapshot(null);
        assertNull(s.getContextSnapshot());
    }

    @Test
    void dryRunSession_settersAndGetters() {
        DryRunSession d = new DryRunSession();
        d.setTenantId(1L);
        d.setRuleVersionId(99L);
        d.setStatus("HIT");

        assertEquals(1L, d.getTenantId());
        assertEquals(99L, d.getRuleVersionId());
        assertEquals("HIT", d.getStatus());
    }

    @Test
    void dryRunSession_contextSnapshot_setAndGet() {
        DryRunSession d = new DryRunSession();
        d.setContextSnapshot("{\"metrics\":{\"user.age\":30,\"order.amount\":5000},\"evalNow\":\"2024-01-01T00:00:00Z\"}");
        assertEquals("{\"metrics\":{\"user.age\":30,\"order.amount\":5000},\"evalNow\":\"2024-01-01T00:00:00Z\"}", d.getContextSnapshot());
    }

    @Test
    void dryRunSession_contextSnapshot_defaultsToNull() {
        DryRunSession d = new DryRunSession();
        assertNull(d.getContextSnapshot());
    }

    @Test
    void dryRunSession_contextSnapshot_setNullAllowed() {
        DryRunSession d = new DryRunSession();
        d.setContextSnapshot("{\"k\":1}");
        d.setContextSnapshot(null);
        assertNull(d.getContextSnapshot());
    }
}
