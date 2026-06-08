package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class DoubleComparisonStrategyTest {
    private final DoubleComparisonStrategy s = new DoubleComparisonStrategy();

    @Test void doubleFastPath() {
        assertThat(s.compare(2.5d, 2.0d)).isPositive();
        assertThat(s.compare(1, 1.0d)).isZero();           // Integer actual widened
        assertThat(s.compare(2.0d, "3.5")).isNegative();   // String operand
        assertThat(s.equals(1.5d, 1.5d)).isTrue();
        assertThat(s.equals(1.5d, 2.0d)).isFalse();
    }
    @Test void nanInfinity_sentinel_notHit() {
        assertThat(s.compare(Double.NaN, 1.0d)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.compare(Double.POSITIVE_INFINITY, 1.0d)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.compare(1.0d, Double.NaN)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.equals(Double.NaN, Double.NaN)).isFalse();
    }
    @Test void bigDecimalOperand_fallsBackToDecimal() {
        assertThat(s.compare(new BigDecimal("2.5"), 2.0d)).isPositive();
        assertThat(s.compare(2.0d, new BigDecimal("2.5"))).isNegative();
    }
    @Test void nullOrUnparsable_sentinel() {
        assertThat(s.compare(null, 1.0d)).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.compare(1.0d, "x")).isEqualTo(Integer.MAX_VALUE);
        assertThat(s.equals(null, 1.0d)).isFalse();
    }
}
