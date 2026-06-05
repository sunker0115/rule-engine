package com.sstlfsj.rule.config.internal.publish;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RolloutParamsTest {

    @Test
    void from_allFieldsPresent_parsesCorrectly() {
        Map<String, Object> params = Map.of(
                "percentage", 50,
                "bucketStart", 10,
                "bucketEnd", 60,
                "experimentId", "exp-abc");
        RolloutParams result = RolloutParams.from(params);
        assertThat(result.percentage()).isEqualTo(50);
        assertThat(result.bucketStart()).isEqualTo(10);
        assertThat(result.bucketEnd()).isEqualTo(60);
        assertThat(result.experimentId()).isEqualTo("exp-abc");
    }

    @Test
    void from_missingFields_returnsNulls() {
        Map<String, Object> params = Map.of();
        RolloutParams result = RolloutParams.from(params);
        assertThat(result.percentage()).isNull();
        assertThat(result.bucketStart()).isNull();
        assertThat(result.bucketEnd()).isNull();
        assertThat(result.experimentId()).isNull();
    }

    @Test
    void from_stringNumberValues_parsesAsInt() {
        Map<String, Object> params = Map.of(
                "percentage", "75",
                "bucketStart", "0",
                "bucketEnd", "100");
        RolloutParams result = RolloutParams.from(params);
        assertThat(result.percentage()).isEqualTo(75);
        assertThat(result.bucketStart()).isEqualTo(0);
        assertThat(result.bucketEnd()).isEqualTo(100);
    }

    @Test
    void from_doubleNumberValue_truncatesToInt() {
        Map<String, Object> params = Map.of("percentage", 30.9);
        RolloutParams result = RolloutParams.from(params);
        // Number.intValue() 截断小数
        assertThat(result.percentage()).isEqualTo(30);
    }

    @Test
    void from_experimentIdNull_returnsNull() {
        Map<String, Object> params = new HashMap<>();
        params.put("experimentId", null);
        RolloutParams result = RolloutParams.from(params);
        assertThat(result.experimentId()).isNull();
    }

    @Test
    void from_invalidStringNumber_throwsNumberFormat() {
        Map<String, Object> params = Map.of("percentage", "abc");
        assertThatThrownBy(() -> RolloutParams.from(params))
                .isInstanceOf(NumberFormatException.class);
    }
}
