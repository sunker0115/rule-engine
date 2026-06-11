package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleVersionStatus 取值集与持久化字面量契约（name() == DB ENUM 值）。 */
class RuleVersionStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(RuleVersionStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("DRAFT", "ACTIVE", "SUPERSEDED", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(RuleVersionStatus.DRAFT.name()).isEqualTo("DRAFT");
        assertThat(RuleVersionStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(RuleVersionStatus.SUPERSEDED.name()).isEqualTo("SUPERSEDED");
        assertThat(RuleVersionStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
