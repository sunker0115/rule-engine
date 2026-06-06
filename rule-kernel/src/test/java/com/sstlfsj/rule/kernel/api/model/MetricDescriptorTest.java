package com.sstlfsj.rule.kernel.api.model;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDescriptorTest {

    @Test
    void nullParams_normalizedToEmptyImmutableMap() {
        MetricDescriptor d = new MetricDescriptor("balance", 1, "SQL_AGGREGATE", "LONG", false, 60, null);
        assertThat(d.params()).isEmpty();
    }

    @Test
    void holdsAllFields() {
        MetricDescriptor d = new MetricDescriptor("balance", 1, "SQL_AGGREGATE", "LONG", true, 0,
                Map.of("datasource", "risk_ro"));
        assertThat(d.sourceType()).isEqualTo("SQL_AGGREGATE");
        assertThat(d.allowProvided()).isTrue();
        assertThat(d.cacheTtlSeconds()).isZero();
        assertThat(d.params()).containsEntry("datasource", "risk_ro");
    }

    /** 6 参便利构造：metricVersion 默认填充为 1。 */
    @Test
    void sixArgConstructor_defaultsVersionToOne() {
        MetricDescriptor d = new MetricDescriptor("balance", "SQL_AGGREGATE", "LONG", false, 60, null);
        assertThat(d.metricVersion()).isEqualTo(1);
        assertThat(d.metricCode()).isEqualTo("balance");
        assertThat(d.params()).isEmpty();
    }

    /** 6 参便利构造与 7 参构造产出的 descriptor 在 metricVersion=1 时语义等价。 */
    @Test
    void sixArgConstructor_equivalentToSevenArgWithVersionOne() {
        MetricDescriptor six = new MetricDescriptor("score", "CALLBACK", "DOUBLE", true, 30, Map.of("k", "v"));
        MetricDescriptor seven = new MetricDescriptor("score", 1, "CALLBACK", "DOUBLE", true, 30, Map.of("k", "v"));
        assertThat(six.metricVersion()).isEqualTo(seven.metricVersion());
        assertThat(six.sourceType()).isEqualTo(seven.sourceType());
        assertThat(six.params()).isEqualTo(seven.params());
    }
}
