package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;

class DateTimeComparisonStrategyTest {

    private final ComparisonStrategy s = new DateTimeComparisonStrategy();

    @Test
    void compare_before_negative() {
        assertThat(s.compare(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isNegative();
    }

    @Test
    void compare_equal_zero() {
        assertThat(s.compare(Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isZero();
    }

    @Test
    void equals_true() {
        assertThat(s.equals(Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isTrue();
    }

    @Test
    void compare_nonInstant_sentinel() {
        assertThat(s.compare("2026-06-01T00:00:00Z", Instant.EPOCH)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void equals_nonInstant_false() {
        assertThat(s.equals("x", Instant.EPOCH)).isFalse();
    }
}
