package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** DecisionStrategy 取值集与持久化字面量契约（name() == DB varchar 值）。 */
class DecisionStrategyTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(DecisionStrategy.values()).map(Enum::name))
                .containsExactlyInAnyOrder("HIGHEST_PRIORITY", "ALL_HITS", "FIRST_HIT");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(DecisionStrategy.HIGHEST_PRIORITY.name()).isEqualTo("HIGHEST_PRIORITY");
        assertThat(DecisionStrategy.ALL_HITS.name()).isEqualTo("ALL_HITS");
        assertThat(DecisionStrategy.FIRST_HIT.name()).isEqualTo("FIRST_HIT");
    }
}
