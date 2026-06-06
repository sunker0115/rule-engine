package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultComparisonStrategyTest {

    private final DefaultComparisonStrategy strategy = new DefaultComparisonStrategy();

    @Test
    void equals_integerActual_usesNumericPath() {
        // actual 是 Number -> 走数值路径，100 == 100
        assertThat(strategy.equals(100, 100)).isTrue();
    }

    @Test
    void equals_doubleActual_usesNumericPath() {
        assertThat(strategy.equals(99.5, 99.5)).isTrue();
    }

    @Test
    void equals_booleanActual_usesBooleanPath() {
        assertThat(strategy.equals(true, "true")).isTrue();
        assertThat(strategy.equals(false, true)).isFalse();
    }

    @Test
    void equals_stringActual_usesStringPath() {
        // actual 是 String -> 走字符串路径
        assertThat(strategy.equals("0100", "100")).isFalse();
        assertThat(strategy.equals("ACTIVE", "ACTIVE")).isTrue();
    }

    @Test
    void compare_numberActual_usesNumericPath() {
        assertThat(strategy.compare(50, 100)).isLessThan(0);
        assertThat(strategy.compare(100, 50)).isGreaterThan(0);
    }

    @Test
    void compare_stringActual_usesStringPath() {
        assertThat(strategy.compare("abc", "abd")).isLessThan(0);
    }

    @Test
    void compare_booleanActual_throwsUnsupportedOperation() {
        assertThatThrownBy(() -> strategy.compare(true, false))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
