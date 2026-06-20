package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class EventDedupFnTest {

    private KeyedOneInputStreamOperatorTestHarness<String, TradeEvent, TradeEvent> harness() throws Exception {
        KeyedProcessOperator<String, TradeEvent, TradeEvent> op = new KeyedProcessOperator<>(new EventDedupFn());
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, TradeEvent::customerId, Types.STRING);
        h.open();
        return h;
    }

    private TradeEvent trade(String eventId) {
        return new TradeEvent("c1", "BTC", new BigDecimal("100.00"), "API", Instant.parse("2026-06-20T07:00:00Z"), eventId);
    }

    @Test
    void forwardsFirstDropsDuplicate() throws Exception {
        var h = harness();
        h.processElement(trade("e1"), 1);
        h.processElement(trade("e1"), 2);   // 同 eventId → drop
        h.processElement(trade("e2"), 3);   // 新 eventId → forward
        assertThat(h.extractOutputValues()).hasSize(2);
        h.close();
    }

    @Test
    void forwardsWhenEventIdBlank() throws Exception {
        var h = harness();
        TradeEvent noId = new TradeEvent("c1", "BTC", new BigDecimal("1"), "API", Instant.parse("2026-06-20T07:00:00Z"), "");
        h.processElement(noId, 1);
        h.processElement(noId, 2);   // 空 eventId 不过滤，全 forward
        assertThat(h.extractOutputValues()).hasSize(2);
        h.close();
    }
}
