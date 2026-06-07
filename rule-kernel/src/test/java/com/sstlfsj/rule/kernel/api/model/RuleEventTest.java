package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleEventTest {

    private static RuleEvent.RuleEventBuilder base() {
        return RuleEvent.builder()
                .tenantId("t1").sceneCode("s1").eventType("LOGIN")
                .subjectId("u1").eventId("evt-1").source(EventSource.HTTP);
    }

    @Test
    void builderMinimalFillsDefaults() {
        RuleEvent e = base().build();
        assertThat(e.source()).isEqualTo(EventSource.HTTP);
        assertThat(e.occurredAt()).isNotNull();   // 缺省 now
        assertThat(e.payload()).isEmpty();         // 缺省空
        assertThat(e.providedMetrics()).isEmpty();
    }

    @Test
    void builderCarriesPayloadAndMetrics() {
        RuleEvent e = base()
                .payload(Map.of("k", "v")).providedMetrics(Map.of("fts", 0.8))
                .source(EventSource.JOB).build();
        assertThat(e.payload()).containsEntry("k", "v");
        assertThat(e.providedMetrics()).containsEntry("fts", 0.8);
        assertThat(e.source()).isEqualTo(EventSource.JOB);
    }

    @Test
    void rejectsNullSource() {
        assertThatThrownBy(() -> base().source(null).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void payloadImmutableAndDefensivelyCopied() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("k", "v");
        RuleEvent e = base().payload(mutable).build();
        mutable.put("extra", "x");
        assertThat(e.payload()).hasSize(1);   // 改原 map 不影响
        assertThatThrownBy(() -> e.payload().put("a", "b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void providedMetricsDefensivelyCopied() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("balance", 100);
        RuleEvent e = base().providedMetrics(mutable).build();
        mutable.put("extra", "x");
        assertThat(e.providedMetrics()).hasSize(1);
    }

    @Test
    void recordEqualityByValue() {
        Instant now = Instant.EPOCH;
        RuleEvent a = base().occurredAt(now).build();
        RuleEvent b = base().occurredAt(now).build();
        assertThat(a).isEqualTo(b);
    }
}
