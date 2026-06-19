package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** TaskStatus 取值集与持久化字面量契约(name() == DB 列值)。 */
class TaskStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(TaskStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("ACTIVE", "DISABLED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(TaskStatus.ACTIVE.name()).isEqualTo("ACTIVE");
        assertThat(TaskStatus.DISABLED.name()).isEqualTo("DISABLED");
    }
}
