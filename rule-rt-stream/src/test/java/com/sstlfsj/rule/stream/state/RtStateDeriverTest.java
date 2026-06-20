package com.sstlfsj.rule.stream.state;

import com.sstlfsj.rule.stream.model.FeatureSnapshot;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RtStateDeriverTest {

    private FeatureSnapshot snap(long rtm1s, double fastRatio, double sus) {
        FeatureSnapshot s = new FeatureSnapshot("test");
        s.rtmMwr1s = rtm1s; s.fastTradeRatio = fastRatio; s.susScore = sus;
        return s;
    }

    @Test void latencyArb() { assertThat(RtStateDeriver.derive(snap(9, 0.9, 0.8))).isEqualTo(RtStateDeriver.LATENCY_ARB); }
    @Test void shortAlpha() { assertThat(RtStateDeriver.derive(snap(12, 0.5, 0.7))).isEqualTo(RtStateDeriver.SHORT_ALPHA); }
    @Test void watchBySus() { assertThat(RtStateDeriver.derive(snap(3, 0.2, 0.35))).isEqualTo(RtStateDeriver.RT_WATCH); }
    @Test void watchByRatio() { assertThat(RtStateDeriver.derive(snap(3, 0.4, 0.1))).isEqualTo(RtStateDeriver.RT_WATCH); }
    @Test void clean() { assertThat(RtStateDeriver.derive(snap(2, 0.1, 0.1))).isEqualTo(RtStateDeriver.RT_CLEAN); }
}
