package com.sstlfsj.rule.eval.internal.metric.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDataSourceRegistryTest {

    @Test
    void emptyConfig_registersNothing() {
        FetchResourceProperties props = new FetchResourceProperties();
        props.setDatasources(List.of());
        try (MetricDataSourceRegistry reg = new MetricDataSourceRegistry(props)) {
            assertThat(reg.template("nope")).isNull();
            assertThat(reg.isRegistered("nope")).isFalse();
            assertThat(reg.names()).isEmpty();
        }
    }
}
