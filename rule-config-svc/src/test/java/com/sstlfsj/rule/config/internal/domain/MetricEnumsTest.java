package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** MetricEnums 允许值断言（单一真相源，DECIMAL 已纳入 data_type）。 */
class MetricEnumsTest {

    @Test
    void dataTypes_containsDecimalAndCoreTypes() {
        assertThat(MetricEnums.DATA_TYPES).containsExactlyInAnyOrder(
                "LONG", "DOUBLE", "DECIMAL", "STRING", "BOOLEAN", "LIST", "DATE", "DATETIME");
    }

    @Test
    void sourceTypes_matchAllowedSet() {
        assertThat(MetricEnums.SOURCE_TYPES).containsExactlyInAnyOrder(
                "ATTRIBUTE", "SQL_AGGREGATE", "EXTERNAL_HTTP", "STREAM");
    }

    @Test
    void statuses_matchAllowedSet() {
        assertThat(MetricEnums.STATUSES).containsExactlyInAnyOrder("ACTIVE", "DISABLED");
    }
}
