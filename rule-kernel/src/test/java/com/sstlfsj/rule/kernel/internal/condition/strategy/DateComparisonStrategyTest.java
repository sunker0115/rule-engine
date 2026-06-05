package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class DateComparisonStrategyTest {

    private final ComparisonStrategy s = new DateComparisonStrategy();

    @Test
    void compare_before_negative() {
        assertThat(s.compare(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1))).isNegative();
    }

    @Test
    void compare_equal_zero() {
        assertThat(s.compare(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))).isZero();
    }

    @Test
    void equals_true() {
        assertThat(s.equals(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))).isTrue();
    }

    @Test
    void compare_nonLocalDate_sentinel() {
        assertThat(s.compare("2026-06-01", LocalDate.of(2026, 6, 1))).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void equals_nonLocalDate_false() {
        assertThat(s.equals("x", LocalDate.of(2026, 6, 1))).isFalse();
    }
}
