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
}
