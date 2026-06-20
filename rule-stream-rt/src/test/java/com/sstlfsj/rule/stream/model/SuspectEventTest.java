package com.sstlfsj.rule.stream.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SuspectEventTest {

    @Test
    void defaultConstructorAndFieldAssignment() {
        SuspectEvent e = new SuspectEvent();
        e.customerId = "c1";
        e.rtmMwr1s = 1;
        e.rtmMwr10s = 2;
        e.rtmMwr1m = 3;
        e.rtmMwr5m = 4;
        e.rtdAmountSum = 500.0;
        e.fastTradeRatio = 0.8;
        e.susScore = 0.7;
        e.rtState = "RT_WATCH";
        e.suspectId = "c1-100";
        e.occurredAt = Instant.parse("2026-06-20T07:00:00Z");

        assertThat(e.customerId).isEqualTo("c1");
        assertThat(e.rtmMwr1s).isEqualTo(1);
        assertThat(e.susScore).isEqualTo(0.7);
        assertThat(e.suspectId).isEqualTo("c1-100");
        assertThat(e.occurredAt).isEqualTo(Instant.parse("2026-06-20T07:00:00Z"));
    }
}
