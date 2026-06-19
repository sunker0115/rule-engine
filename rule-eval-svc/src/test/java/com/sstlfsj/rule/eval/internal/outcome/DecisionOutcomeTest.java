package com.sstlfsj.rule.eval.internal.outcome;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DecisionOutcomeTest {

    @Test
    void settersAndGetters_roundTrip() {
        DecisionOutcome o = new DecisionOutcome();
        o.setId(1L);
        o.setTenantId(7L);
        o.setEventId("evt-1");
        o.setOutcomeLabel("FRAUD");
        o.setOutcomeValue(new BigDecimal("1280.50"));
        o.setOutcomeNote("chargeback");
        LocalDateTime t = LocalDateTime.of(2026, 6, 18, 10, 0);
        o.setLabeledAt(t);
        o.setSource("ops");

        assertEquals(1L, o.getId());
        assertEquals(7L, o.getTenantId());
        assertEquals("evt-1", o.getEventId());
        assertEquals("FRAUD", o.getOutcomeLabel());
        assertEquals(new BigDecimal("1280.50"), o.getOutcomeValue());
        assertEquals("chargeback", o.getOutcomeNote());
        assertEquals(t, o.getLabeledAt());
        assertEquals("ops", o.getSource());
    }

    @Test
    void optionalFields_defaultNull() {
        DecisionOutcome o = new DecisionOutcome();
        assertNull(o.getOutcomeValue());
        assertNull(o.getOutcomeNote());
        assertNull(o.getSource());
    }
}
