package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ComparisonStrategyFactoryTest {

    @Test
    void forType_long_returnsNumeric() {
        assertThat(ComparisonStrategyFactory.forType("LONG"))
                .isInstanceOf(NumericComparisonStrategy.class);
    }

    @Test
    void forType_double_returnsNumeric() {
        assertThat(ComparisonStrategyFactory.forType("DOUBLE"))
                .isInstanceOf(NumericComparisonStrategy.class);
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
}
