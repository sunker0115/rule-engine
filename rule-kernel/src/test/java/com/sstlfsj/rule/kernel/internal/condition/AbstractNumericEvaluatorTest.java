package com.sstlfsj.rule.kernel.internal.condition;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractNumericEvaluatorTest {

    @Test
    void toNumber_integer_returnsNumber() {
        assertThat(AbstractNumericEvaluator.toNumber(42)).isEqualTo(42);
    }

    @Test
    void toNumber_double_returnsNumber() {
        assertThat(AbstractNumericEvaluator.toNumber(3.14)).isEqualTo(3.14);
    }

    @Test
    void toNumber_longString_parsesAsLong() {
        Number n = AbstractNumericEvaluator.toNumber("100");
        assertThat(n.longValue()).isEqualTo(100L);
    }

    @Test
    void toNumber_doubleString_parsesAsDouble() {
        Number n = AbstractNumericEvaluator.toNumber("1.5");
        assertThat(n.doubleValue()).isEqualTo(1.5);
    }

    @Test
    void toNumber_nonNumericString_returnsNull() {
        assertThat(AbstractNumericEvaluator.toNumber("abc")).isNull();
    }

    @Test
    void toNumber_null_returnsNull() {
        assertThat(AbstractNumericEvaluator.toNumber(null)).isNull();
    }

    @Test
    void toNumber_booleanObject_returnsNull() {
        assertThat(AbstractNumericEvaluator.toNumber(true)).isNull();
    }
}
