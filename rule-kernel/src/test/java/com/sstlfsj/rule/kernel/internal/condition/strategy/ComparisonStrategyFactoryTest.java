package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonStrategyFactoryTest {

    @Test
    void forType_long_returnsLong() {
        assertThat(ComparisonStrategyFactory.forType("LONG"))
                .isInstanceOf(LongComparisonStrategy.class);
    }

    @Test
    void forType_double_returnsNumeric() {
        assertThat(ComparisonStrategyFactory.forType("DOUBLE"))
                .isInstanceOf(DecimalComparisonStrategy.class);
    }

    @Test
    void forType_decimal_returnsDecimal() {
        assertThat(ComparisonStrategyFactory.forType("DECIMAL"))
                .isInstanceOf(DecimalComparisonStrategy.class);
    }

    @Test
    void forType_string_returnsString() {
        assertThat(ComparisonStrategyFactory.forType("STRING"))
                .isInstanceOf(StringComparisonStrategy.class);
    }

    @Test
    void forType_boolean_returnsBoolean() {
        assertThat(ComparisonStrategyFactory.forType("BOOLEAN"))
                .isInstanceOf(BooleanComparisonStrategy.class);
    }

    @Test
    void forType_null_returnsDefault() {
        assertThat(ComparisonStrategyFactory.forType(null))
                .isInstanceOf(DefaultComparisonStrategy.class);
    }

    @Test
    void forType_list_returnsDefault() {
        assertThat(ComparisonStrategyFactory.forType("LIST"))
                .isInstanceOf(DefaultComparisonStrategy.class);
    }

    @Test
    void forType_unknown_returnsDefault() {
        assertThat(ComparisonStrategyFactory.forType("UNKNOWN"))
                .isInstanceOf(DefaultComparisonStrategy.class);
    }

    @Test
    void forType_returnsCachedSingleton() {
        // 相同 dataType 调用两次，返回同一实例（缓存单例，零分配）
        assertThat(ComparisonStrategyFactory.forType("LONG"))
                .isSameAs(ComparisonStrategyFactory.forType("LONG"));
        assertThat(ComparisonStrategyFactory.forType(null))
                .isSameAs(ComparisonStrategyFactory.forType(null));
    }

    @Test
    void forType_date_returnsDateStrategy() {
        ComparisonStrategy s = ComparisonStrategyFactory.forType("DATE");
        assertThat(s.equals(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1))).isTrue();
        assertThat(s.compare(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1))).isNegative();
    }

    @Test
    void forType_datetime_returnsDateTimeStrategy() {
        ComparisonStrategy s = ComparisonStrategyFactory.forType("DATETIME");
        assertThat(s.equals(Instant.EPOCH, Instant.EPOCH)).isTrue();
        assertThat(s.compare(Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"))).isNegative();
    }

    @Test
    void forType_datetime_rejectsNonInstant_withSentinel() {
        // DATETIME 策略对非 Instant 返回 MAX_VALUE 哨兵（DEFAULT 策略不会）
        assertThat(ComparisonStrategyFactory.forType("DATETIME").compare("a", "b"))
                .isEqualTo(Integer.MAX_VALUE);
    }
}
