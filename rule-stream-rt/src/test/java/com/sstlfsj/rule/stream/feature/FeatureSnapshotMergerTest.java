package com.sstlfsj.rule.stream.feature;

import com.sstlfsj.rule.stream.model.FeatureField;
import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.PartialFeature;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureSnapshotMergerTest {

    private KeyedOneInputStreamOperatorTestHarness<String, PartialFeature, FeatureSnapshot> harness() throws Exception {
        KeyedProcessOperator<String, PartialFeature, FeatureSnapshot> op =
                new KeyedProcessOperator<>(new FeatureSnapshotMerger());
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, p -> p.customerId, Types.STRING);
        h.open();
        return h;
    }

    @Test
    void mergesFieldsAndFiresOnTimer() throws Exception {
        var h = harness();
        // 同客户先后到 1s 计数 9、API 占比 0.9，eventTime 同为 1000ms
        h.processElement(new PartialFeature("c1", FeatureField.RTM_1S, 9, 1000), 1000);
        h.processElement(new PartialFeature("c1", FeatureField.FAST_TRADE_RATIO, 0.9, 1000), 1000);
        h.processWatermark(2000);   // 推进过 1000+200，触发 timer

        var out = h.extractOutputValues();
        assertThat(out).isNotEmpty();
        FeatureSnapshot last = out.get(out.size() - 1);
        assertThat(last.rtmMwr1s).isEqualTo(9);
        assertThat(last.fastTradeRatio).isEqualTo(0.9);
        assertThat(last.rtState).isEqualTo("LATENCY_ARB");
        assertThat(last.updatedAt).isEqualTo(1L);   // (1000+200)/1000 → event-time 秒
        h.close();
    }

    @Test
    void zeroOverwritesFallsBack() throws Exception {
        var h = harness();
        h.processElement(new PartialFeature("c1", FeatureField.RTM_1S, 9, 1000), 1000);
        h.processWatermark(1300);
        h.processElement(new PartialFeature("c1", FeatureField.RTM_1S, 0, 2000), 2000);  // 回落
        h.processWatermark(2300);

        var out = h.extractOutputValues();
        assertThat(out.get(out.size() - 1).rtmMwr1s).isEqualTo(0);   // 覆盖写，回落到 0
        h.close();
    }
}
