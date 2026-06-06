package com.sstlfsj.rule.eval.internal.metric;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DataTypeCoercionTest {

    @Test
    void longFromBigDecimal() {
        assertThat(DataTypeCoercion.coerce(new BigDecimal("42"), "LONG")).isEqualTo(42L);
    }

    @Test
    void doubleFromInteger() {
        assertThat(DataTypeCoercion.coerce(7, "DOUBLE")).isEqualTo(7.0d);
    }

    @Test
    void stringFromNumber() {
        assertThat(DataTypeCoercion.coerce(5L, "STRING")).isEqualTo("5");
    }

    @Test
    void booleanFromNumber() {
        assertThat(DataTypeCoercion.coerce(1, "BOOLEAN")).isEqualTo(true);
        assertThat(DataTypeCoercion.coerce(0, "BOOLEAN")).isEqualTo(false);
    }

    @Test
    void nullStaysNull() {
        assertThat(DataTypeCoercion.coerce(null, "LONG")).isNull();
    }
}
