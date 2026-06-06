package com.sstlfsj.rule.eval.internal.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetricDefinitionRowTest {

    @Test
    void fields_accessible() {
        MetricDefinitionRow row = new MetricDefinitionRow(
                "balance", "SQL_AGGREGATE", "LONG", false, 30, "{\"datasource\":\"ro\"}");
        assertThat(row.metricCode()).isEqualTo("balance");
        assertThat(row.sourceType()).isEqualTo("SQL_AGGREGATE");
        assertThat(row.cacheTtlSeconds()).isEqualTo(30);
        assertThat(row.allowProvided()).isFalse();
        assertThat(row.paramsJson()).contains("datasource");
    }
}
