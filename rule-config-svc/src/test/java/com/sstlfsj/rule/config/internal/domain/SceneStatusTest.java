package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** SceneStatus 取值集与持久化字面量契约（name() == DB ENUM 值）。 */
class SceneStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(SceneStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(SceneStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(SceneStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
