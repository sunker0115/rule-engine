package com.sstlfsj.rule.bridge.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SuspectPayloadTest {

    @Test
    void holdsAllFieldsAlignedWithSuspectEvent() {
        Instant t = Instant.parse("2026-06-20T07:00:00Z");
        SuspectPayload p = new SuspectPayload("c1", 1, 2, 3, 4, 500.0, 0.8, 0.7,
                "SHORT_ALPHA", "c1-100", t);

        assertThat(p.customerId()).isEqualTo("c1");
        assertThat(p.rtmMwr1s()).isEqualTo(1);
        assertThat(p.rtmMwr10s()).isEqualTo(2);
        assertThat(p.rtmMwr1m()).isEqualTo(3);
        assertThat(p.rtmMwr5m()).isEqualTo(4);
        assertThat(p.rtdAmountSum()).isEqualTo(500.0);
        assertThat(p.fastTradeRatio()).isEqualTo(0.8);
        assertThat(p.susScore()).isEqualTo(0.7);
        assertThat(p.rtState()).isEqualTo("SHORT_ALPHA");
        assertThat(p.suspectId()).isEqualTo("c1-100");
        assertThat(p.occurredAt()).isEqualTo(t);
    }
}
