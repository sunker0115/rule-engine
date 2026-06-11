package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** ActorType 取值集与持久化字面量契约（name() == DB varchar 值）。 */
class ActorTypeTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(ActorType.values()).map(Enum::name))
                .containsExactlyInAnyOrder("USER", "SYSTEM", "JOB");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(ActorType.USER.name()).isEqualTo("USER");
        assertThat(ActorType.SYSTEM.name()).isEqualTo("SYSTEM");
        assertThat(ActorType.JOB.name()).isEqualTo("JOB");
    }
}
