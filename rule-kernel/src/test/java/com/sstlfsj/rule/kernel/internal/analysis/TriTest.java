package com.sstlfsj.rule.kernel.internal.analysis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Tri 三值逻辑枚举的基本约束：三个取值齐备且互不相等。 */
class TriTest {

    @Test
    void hasThreeDistinctValues() {
        assertThat(Tri.values()).containsExactly(Tri.TRUE, Tri.FALSE, Tri.UNKNOWN);
    }

    @Test
    void valueOf_roundTrips() {
        assertThat(Tri.valueOf("UNKNOWN")).isEqualTo(Tri.UNKNOWN);
        assertThat(Tri.TRUE).isNotEqualTo(Tri.FALSE);
    }
}
