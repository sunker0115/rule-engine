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
        s.setStatus(SessionStatus.PENDING);
        s.setOccurredAt(LocalDateTime.now());

        assertEquals(1L, s.getTenantId());
        assertEquals("evt-001", s.getEventId());
        assertEquals("fraud_check", s.getSceneCode());
        assertEquals(SessionStatus.PENDING, s.getStatus());
        assertNotNull(s.getOccurredAt());
    }

    @Test
    void contextSnapshot_setAndGet() {
        EvaluationSession s = new EvaluationSession();
        EvaluationContextSnapshot snapshot = new EvaluationContextSnapshot(java.util.Map.of("user.age", 25), java.time.Instant.parse("2024-01-01T00:00:00Z"));
        s.setContextSnapshot(snapshot);
        assertEquals(snapshot, s.getContextSnapshot());
    }

    @Test
    void score_setAndGet() {
        EvaluationSession s = new EvaluationSession();
        assertNull(s.getScore());          // 默认 null（AST_BOOLEAN 等无分场景）
        s.setScore(87.5);
        assertEquals(87.5, s.getScore());
    }

    @Test
    void category_setAndGet() {
        EvaluationSession s = new EvaluationSession();
        assertNull(s.getCategory());
        s.setCategory("中危");
        assertEquals("中危", s.getCategory());
    }

    @Test
    void contextSnapshot_defaultsToNull() {
        EvaluationSession s = new EvaluationSession();
        assertNull(s.getContextSnapshot());
    }

    @Test
    void contextSnapshot_setNullAllowed() {
        EvaluationSession s = new EvaluationSession();
        s.setContextSnapshot(new EvaluationContextSnapshot(java.util.Map.of("k", 1), null));
        s.setContextSnapshot(null);
        assertNull(s.getContextSnapshot());
    }

    @Test
    void replayColumns_setAndGet() {
        EvaluationSession s = new EvaluationSession();
        assertNull(s.getPayload());                  // 默认 null（未捕获）
        assertNull(s.getCandidateRuleVersionIds());
        s.setPayload(java.util.Map.of("amount", 5000));
        s.setCandidateRuleVersionIds(java.util.List.of(11L, 22L));
        assertEquals(java.util.Map.of("amount", 5000), s.getPayload());
        assertEquals(java.util.List.of(11L, 22L), s.getCandidateRuleVersionIds());
    }

    @Test
    void replayColumns_setNullAllowed() {
        EvaluationSession s = new EvaluationSession();
        s.setPayload(java.util.Map.of("k", 1));
        s.setPayload(null);
        s.setCandidateRuleVersionIds(java.util.List.of(1L));
        s.setCandidateRuleVersionIds(null);
        assertNull(s.getPayload());
        assertNull(s.getCandidateRuleVersionIds());
    }
}
