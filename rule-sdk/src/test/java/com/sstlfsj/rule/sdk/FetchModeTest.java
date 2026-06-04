package com.sstlfsj.rule.sdk;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FetchModeTest {

    @Test
    void allValuesExist() {
        assertThat(FetchMode.values()).containsExactlyInAnyOrder(
                FetchMode.DECLARED, FetchMode.ALL, FetchMode.LAZY);
    }
}
