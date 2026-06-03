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
    void dryRunSession_settersAndGetters() {
        DryRunSession d = new DryRunSession();
        d.setTenantId(1L);
        d.setRuleVersionId(99L);
        d.setStatus("HIT");

        assertEquals(1L, d.getTenantId());
        assertEquals(99L, d.getRuleVersionId());
        assertEquals("HIT", d.getStatus());
    }
}
