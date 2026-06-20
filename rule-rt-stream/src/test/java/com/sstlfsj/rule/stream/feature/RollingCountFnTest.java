package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.PartialFeature;
import com.sstlfsj.rule.stream.model.SecondCount;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RollingCountFnTest {

    private KeyedOneInputStreamOperatorTestHarness<String, SecondCount, PartialFeature> harness() throws Exception {
        KeyedProcessOperator<String, SecondCount, PartialFeature> op =
                new KeyedProcessOperator<>(new RollingCountFn());
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, sc -> sc.customerId, Types.STRING);
        h.open();
        return h;
    }

    private long latest(List<PartialFeature> out, FeatureField field) {
        return out.stream()
                .filter(p -> p.field == field)
                .reduce((a, b) -> b)          // 取最后一个
                .map(p -> (long) p.value)
                .orElse(-1L);
    }

    @Test
    void emitsRollingSumsOnElement() throws Exception {
        var h = harness();
        // 秒 100 来 9 笔
        h.processElement(new SecondCount("c1", 9, 100), 100_000);
        var out = h.extractOutputValues();
        assertThat(latest(out, FeatureField.RTM_1S)).isEqualTo(9);
        assertThat(latest(out, FeatureField.RTM_5M)).isEqualTo(9);
        h.close();
    }

    /** 核心：高频后无新交易，仅靠 watermark 推进逐秒回落——堵住"只在下一笔交易时回落"的盲区。 */
    @Test
    void fallsBackByWatermarkWithoutNewEvents() throws Exception {
        var h = harness();
        h.processElement(new SecondCount("c1", 9, 100), 100_000);   // 秒 100 爆发 9 笔
        // 此后无任何新 SecondCount，仅推进 watermark
        h.processWatermark(102_000);   // 推进到秒 102，触发 timer 链

        var out = h.extractOutputValues();
        // 秒 101/102 无计数 → 1s 窗口应回落到 0（秒 100 已不在最近 1s 内）
        assertThat(latest(out, FeatureField.RTM_1S)).isEqualTo(0);
        // 10s 窗口仍含秒 100（age 2 < 10）
        assertThat(latest(out, FeatureField.RTM_10S)).isEqualTo(9);
        h.close();
    }

    /** state 全部滑出（超过 5m）后，所有 RT-M 回落到 0。 */
    @Test
    void allFallToZeroAfterMaxWindow() throws Exception {
        var h = harness();
        h.processElement(new SecondCount("c1", 5, 100), 100_000);
        h.processWatermark(401_000);   // 秒 401，秒 100 已滑出 5m（age 301 ≥ 300）

        var out = h.extractOutputValues();
        assertThat(latest(out, FeatureField.RTM_5M)).isEqualTo(0);
        assertThat(latest(out, FeatureField.RTM_1S)).isEqualTo(0);
        h.close();
    }
}
