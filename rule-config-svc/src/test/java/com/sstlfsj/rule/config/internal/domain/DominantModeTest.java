package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** DominantMode 取值集与持久化字面量契约（name() == DB varchar 值）。 */
class DominantModeTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(DominantMode.values()).map(Enum::name))
                .containsExactlyInAnyOrder("PUSH", "PULL", "HYBRID");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(DominantMode.PUSH.name()).isEqualTo("PUSH");
        assertThat(DominantMode.PULL.name()).isEqualTo("PULL");
        assertThat(DominantMode.HYBRID.name()).isEqualTo("HYBRID");
    }
}
