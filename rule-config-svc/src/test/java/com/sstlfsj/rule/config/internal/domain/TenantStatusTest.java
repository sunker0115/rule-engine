package com.sstlfsj.rule.config.internal.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** TenantStatus 取值集与持久化字面量契约（name() == DB varchar 值）。 */
class TenantStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(TenantStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(TenantStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(TenantStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
