package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** MetricStatus 取值集与持久化字面量契约（name() == DB ENUM 值）。 */
class MetricStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(MetricStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "SUPERSEDED", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(MetricStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(MetricStatus.SUPERSEDED.name()).isEqualTo("SUPERSEDED");
        assertThat(MetricStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
