package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** RuleDefinitionStatus 取值集与持久化字面量契约（name() == DB ENUM 值）。 */
class RuleDefinitionStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(RuleDefinitionStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("DRAFT", "PUBLISHING", "PUBLISHED", "PUBLISH_FAILED", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(RuleDefinitionStatus.DRAFT.name()).isEqualTo("DRAFT");
        assertThat(RuleDefinitionStatus.PUBLISHING.name()).isEqualTo("PUBLISHING");
        assertThat(RuleDefinitionStatus.PUBLISHED.name()).isEqualTo("PUBLISHED");
        assertThat(RuleDefinitionStatus.PUBLISH_FAILED.name()).isEqualTo("PUBLISH_FAILED");
        assertThat(RuleDefinitionStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
