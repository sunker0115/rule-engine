package com.sstlfsj.rule.config.api.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImportPolicyTest {

    @Test
    void values_containsThreeStrategies() {
        assertThat(ImportPolicy.values()).containsExactlyInAnyOrder(
                ImportPolicy.SKIP, ImportPolicy.OVERWRITE, ImportPolicy.ABORT);
    }

    @Test
    void valueOf_parsesCorrectly() {
        assertThat(ImportPolicy.valueOf("SKIP")).isEqualTo(ImportPolicy.SKIP);
        assertThat(ImportPolicy.valueOf("OVERWRITE")).isEqualTo(ImportPolicy.OVERWRITE);
        assertThat(ImportPolicy.valueOf("ABORT")).isEqualTo(ImportPolicy.ABORT);
    }
}
