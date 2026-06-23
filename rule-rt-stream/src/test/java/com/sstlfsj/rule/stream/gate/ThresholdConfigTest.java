package com.sstlfsj.rule.stream.gate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdConfigTest {

    @Test
    void defaultSusScoreThresholdIsHalf() {
        assertThat(ThresholdConfig.DEFAULT_SUS_SCORE_THRESHOLD).isEqualTo(0.5);
    }
}
