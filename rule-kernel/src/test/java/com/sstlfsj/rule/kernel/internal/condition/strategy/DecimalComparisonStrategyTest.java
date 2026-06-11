package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalComparisonStrategyTest {

    private final DecimalComparisonStrategy strategy = new DecimalComparisonStrategy();

    @Test
    void compare_integerEqual_returnsZero() {
        assertThat(strategy.compare(100, 100)).isEqualTo(0);
    }

    @Test
    void compare_integerLess_returnsNegative() {
        assertThat(strategy.compare(99, 100)).isLessThan(0);
    }

    @Test
    void compare_integerGreater_returnsPositive() {
        assertThat(strategy.compare(101, 100)).isGreaterThan(0);
    }

    @Test
    void equals_bigDecimalScaleDifference_returnsTrue() {
        // 50000.00 与 50000 应视为相等（BigDecimal.compareTo，不用 equals）
        assertThat(strategy.equals(new BigDecimal("50000.00"), new BigDecimal("50000"))).isTrue();
    }

    @Test
    void equals_largeLong_noDoublePrecisionLoss() {
        // 9007199254740993 超过 double 精度边界，double 会丢失精度
        long a = 9007199254740993L;
        long b = 9007199254740994L;
        assertThat(strategy.equals(a, b)).isFalse();
    }

    @Test
    void equals_numericStringAndInt_returnsTrue() {
        // 数值路径：字符串 "100" 转 BigDecimal 后与 Integer 100 相等
        // （STRING 类型下 "0100"≠"100" 的 bug 修复由 StringComparisonStrategy 负责，不在本策略）
        assertThat(strategy.equals("100", 100)).isTrue();
    }

    @Test
    void compare_nan_returnsSentinel() {
        // Double.NaN 无法转 BigDecimal -> toBigDecimal 返回 null -> compare 返回哨兵 Integer.MAX_VALUE
        // （调用方 AbstractNumericEvaluator 据此判 false），equals 返回 false
        assertThat(strategy.compare(Double.NaN, 1.0)).isEqualTo(Integer.MAX_VALUE);
        assertThat(strategy.equals(Double.NaN, 1.0)).isFalse();
    }

    @Test
    void infinity_equalsFalse_compareSentinel() {
        assertThat(strategy.equals(Double.POSITIVE_INFINITY, 1.0)).isFalse();
        assertThat(strategy.compare(Double.POSITIVE_INFINITY, 1.0)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void nullActual_equalsFalse_compareSentinel() {
        // null -> toBigDecimal 返回 null -> equals false、compare 哨兵
        assertThat(strategy.equals(null, 100)).isFalse();
        assertThat(strategy.compare(null, 100)).isEqualTo(Integer.MAX_VALUE);
    }
}
