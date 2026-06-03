package com.sstlfsj.rule.eval.internal.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class DryRunSessionTest {

    @Test
    void settersAndGetters_roundTrip() {
        DryRunSession d = new DryRunSession();
        d.setTenantId(1L);
        d.setEventId("evt-dry-001");
        d.setSceneCode("fraud_check");
        d.setEventType("PAYMENT");
        d.setSubjectId("user-123");
        d.setRuleVersionId(99L);
        d.setStatus("HIT");
        d.setFinalDecision("BLOCK");
        d.setHitDecisions("[\"rule-1\"]");
        d.setBlockedBy("rule-1");
        d.setErrorCode(null);
        LocalDateTime now = LocalDateTime.now();
        d.setOccurredAt(now);
        d.setStartedAt(now);
        d.setFinishedAt(now.plusSeconds(1));
        d.setEvalDurationMs(42);

        assertEquals(1L, d.getTenantId());
        assertEquals("evt-dry-001", d.getEventId());
        assertEquals("fraud_check", d.getSceneCode());
        assertEquals("PAYMENT", d.getEventType());
        assertEquals("user-123", d.getSubjectId());
        assertEquals(99L, d.getRuleVersionId());
        assertEquals("HIT", d.getStatus());
        assertEquals("BLOCK", d.getFinalDecision());
        assertEquals("[\"rule-1\"]", d.getHitDecisions());
        assertEquals("rule-1", d.getBlockedBy());
        assertNull(d.getErrorCode());
        assertEquals(now, d.getOccurredAt());
        assertEquals(now, d.getStartedAt());
        assertEquals(now.plusSeconds(1), d.getFinishedAt());
        assertEquals(42, d.getEvalDurationMs());
    }
}
