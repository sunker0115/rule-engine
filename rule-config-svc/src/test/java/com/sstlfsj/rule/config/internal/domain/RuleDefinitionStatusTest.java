package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleDefinitionStatus 取值集与持久化字面量契约（name() == DB varchar 值）。 */
class RuleDefinitionStatusTest {

    @Test
    void values_matchClosedSet() {
        // 同步原子发布无中间态：仅 DRAFT / PUBLISHED / DISABLED
        assertThat(Arrays.stream(RuleDefinitionStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("DRAFT", "PUBLISHED", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(RuleDefinitionStatus.DRAFT.name()).isEqualTo("DRAFT");
        assertThat(RuleDefinitionStatus.PUBLISHED.name()).isEqualTo("PUBLISHED");
        assertThat(RuleDefinitionStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
