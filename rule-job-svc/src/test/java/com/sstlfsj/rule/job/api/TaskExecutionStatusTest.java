package com.sstlfsj.rule.job.api;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** TaskExecutionStatus 取值集与持久化字面量契约(name() == DB 列值)。 */
class TaskExecutionStatusTest {

    @Test
    void values_matchClosedSet() {
        assertThat(Arrays.stream(TaskExecutionStatus.values()).map(Enum::name))
                .containsExactlyInAnyOrder("RUNNING", "SUCCESS", "PARTIAL_FAIL", "FAILED");
    }

    @Test
    void name_equalsPersistedLiteral() {
        assertThat(TaskExecutionStatus.RUNNING.name()).isEqualTo("RUNNING");
        assertThat(TaskExecutionStatus.SUCCESS.name()).isEqualTo("SUCCESS");
        assertThat(TaskExecutionStatus.PARTIAL_FAIL.name()).isEqualTo("PARTIAL_FAIL");
        assertThat(TaskExecutionStatus.FAILED.name()).isEqualTo("FAILED");
    }
}
