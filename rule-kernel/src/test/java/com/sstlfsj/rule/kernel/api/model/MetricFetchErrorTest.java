package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MetricFetchErrorTest {
    @Test
    void tagEqualsName() {
        assertThat(MetricFetchError.TIMEOUT.tag()).isEqualTo("TIMEOUT");
    }

    @Test
    void coversSevenCodes() {
        assertThat(MetricFetchError.values()).hasSize(7);
    }
}
