package com.sstlfsj.rule.stream.gate;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import com.sstlfsj.rule.stream.model.SuspectEvent;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.streaming.api.operators.KeyedProcessOperator;
import org.apache.flink.streaming.util.KeyedOneInputStreamOperatorTestHarness;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Stage1GateFnTest {

    private KeyedOneInputStreamOperatorTestHarness<String, FeatureSnapshot, FeatureSnapshot> harness() throws Exception {
        KeyedProcessOperator<String, FeatureSnapshot, FeatureSnapshot> op =
                new KeyedProcessOperator<>(new Stage1GateFn(0.5));
        var h = new KeyedOneInputStreamOperatorTestHarness<>(op, s -> s.customerId, Types.STRING);
        h.open();
        return h;
    }

    private FeatureSnapshot snap(double sus) {
        FeatureSnapshot s = new FeatureSnapshot("c1");
        s.susScore = sus;
        s.rtState = "RT_WATCH";
        s.updatedAt = 100L;
        return s;
    }

    @Test
    void belowThreshold_onlyMainOutput() throws Exception {
        var h = harness();
        h.processElement(snap(0.3), 1);
        assertThat(h.extractOutputValues()).hasSize(1);                       // 主输出有
        assertThat(h.getSideOutput(Stage1GateFn.SUSPECT_OUT)).isNull();       // 侧输出空
        h.close();
    }

    @Test
    void aboveThreshold_mainAndSideOutput() throws Exception {
        var h = harness();
        h.processElement(snap(0.7), 1);
        assertThat(h.extractOutputValues()).hasSize(1);                       // 主输出有
        var side = h.getSideOutput(Stage1GateFn.SUSPECT_OUT);
        assertThat(side).hasSize(1);
        SuspectEvent se = side.peek().getValue();
        assertThat(se.customerId).isEqualTo("c1");
        assertThat(se.susScore).isEqualTo(0.7);
        assertThat(se.suspectId).isEqualTo("c1-100");
        h.close();
    }
}
