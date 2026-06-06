package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MetricDependencyTest {

    @Test
    void holdsCodeAndVersion() {
        MetricDependency d = new MetricDependency("user.account.age.days", 2);
        assertThat(d.metricCode()).isEqualTo("user.account.age.days");
        assertThat(d.metricVersion()).isEqualTo(2);
    }

    @Test
    void equalityByValue() {
        assertThat(new MetricDependency("a", 1)).isEqualTo(new MetricDependency("a", 1));
        assertThat(new MetricDependency("a", 1)).isNotEqualTo(new MetricDependency("a", 2));
    }
}
