package com.sstlfsj.rule.audit.internal.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WindowTotalsRowTest {

    @Test
    void settersAndGetters_roundTrip() {
        WindowTotalsRow r = new WindowTotalsRow();
        r.setBucket("ALL");
        r.setTotalSessions(100);
        r.setLabeledCount(80);
        r.setTotalPositive(20);
        r.setTotalNegative(60);
        r.setBlockedCount(5);

        assertEquals("ALL", r.getBucket());
        assertEquals(100, r.getTotalSessions());
        assertEquals(80, r.getLabeledCount());
        assertEquals(20, r.getTotalPositive());
        assertEquals(60, r.getTotalNegative());
        assertEquals(5, r.getBlockedCount());
    }
}
