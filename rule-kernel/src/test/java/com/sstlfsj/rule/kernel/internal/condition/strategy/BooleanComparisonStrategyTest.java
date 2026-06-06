package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BooleanComparisonStrategyTest {

    private final BooleanComparisonStrategy strategy = new BooleanComparisonStrategy();

    @Test
    void equals_trueAndStringTrue_returnsTrue() {
        assertThat(strategy.equals(true, "true")).isTrue();
    }

    @Test
    void equals_trueAndStringFalse_returnsFalse() {
        assertThat(strategy.equals(true, "false")).isFalse();
    }

    @Test
    void equals_falseAndFalse_returnsTrue() {
        assertThat(strategy.equals(false, false)).isTrue();
    }

    @Test
    void equals_stringTrueAndStringTrue_returnsTrue() {
        assertThat(strategy.equals("true", "true")).isTrue();
    }

    @Test
    void compare_throwsUnsupportedOperationException() {
        assertThatThrownBy(() -> strategy.compare(true, false))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
