package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** DecisionStatus 取值集与持久化字面量契约（name() == DB varchar 值）。 */
class DecisionStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(DecisionStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(DecisionStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(DecisionStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
