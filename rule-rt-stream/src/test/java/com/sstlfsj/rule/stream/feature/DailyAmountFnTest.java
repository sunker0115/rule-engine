package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.TradeEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class DailyAmountFnTest {

    private KeyedOneInputStreamOperatorTestHarness<String, TradeEvent, PartialFeature> harness() throws Exception {
        KeyedProcessOperator<String, TradeEvent, PartialFeature> op =
                new KeyedProcessOperator<>(new DailyAmountFn());
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, TradeEvent::customerId, Types.STRING);
        h.open();
        return h;
    }

    private TradeEvent trade(String amount, String occurredAt) {
        return new TradeEvent("c1", "BTC", new BigDecimal(amount), "API", Instant.parse(occurredAt), "e-" + occurredAt);
    }

    /** 同一日内多笔累加，每笔即 emit 当前累计（日内实时）。 */
    @Test
    void accumulatesWithinDay() throws Exception {
        var h = harness();
        h.processElement(trade("100.00", "2026-06-20T01:00:00Z"), 1);
        h.processElement(trade("250.00", "2026-06-20T02:00:00Z"), 2);

        var out = h.extractOutputValues();
        assertThat(out.get(0).value).isEqualTo(100.0);
        assertThat(out.get(1).value).isEqualTo(350.0);   // 累计
        h.close();
    }

    /** 跨 UTC 自然日自动重置累计。 */
    @Test
    void resetsOnDayBoundary() throws Exception {
        var h = harness();
        h.processElement(trade("100.00", "2026-06-20T23:00:00Z"), 1);
        h.processElement(trade("30.00", "2026-06-21T00:30:00Z"), 2);   // 次日

        var out = h.extractOutputValues();
        assertThat(out.get(0).value).isEqualTo(100.0);
        assertThat(out.get(1).value).isEqualTo(30.0);    // 重置，不含前一日
        h.close();
    }
}
