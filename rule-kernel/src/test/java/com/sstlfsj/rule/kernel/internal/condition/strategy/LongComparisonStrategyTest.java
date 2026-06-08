package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class LongComparisonStrategyTest {
    private final LongComparisonStrategy s = new LongComparisonStrategy();

    @Test void integralFastPath() {
        assertThat(s.compare(5L, 3L)).isPositive();
        assertThat(s.compare(3, 3L)).isZero();            // Integer actual
        assertThat(s.compare(2L, "3")).isNegative();      // String operand parseable to long
        assertThat(s.equals(7L, 7L)).isTrue();
        assertThat(s.equals(7L, 8L)).isFalse();
    }
    @Test void fractionalActual_fallsBackToDecimal_noTruncation() {
        // declared LONG but actual is 3.7: must NOT truncate to 3 (3.7>=3.5 is true; 3>=3.5 false)
        assertThat(s.compare(3.7d, new BigDecimal("3.5"))).isPositive();
        assertThat(s.compare(3.7d, 4L)).isNegative();
    }
    @Test void nullOrUnparsable_sentinel() {
        assertThat(s.compare(null, 1L)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.compare(1L, "x")).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.equals(null, 1L)).isFalse();
    }
}
