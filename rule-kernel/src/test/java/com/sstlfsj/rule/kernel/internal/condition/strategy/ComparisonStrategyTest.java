package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ComparisonStrategy 接口契约：
 * compare 无法完成比较时必须返回哨兵 Integer.MAX_VALUE，调用方据此视为 false。
 * 通过 NumericComparisonStrategy 覆盖该契约（它是唯一会返回哨兵的实现）。
 */
class ComparisonStrategyTest {

    private final ComparisonStrategy strategy = new NumericComparisonStrategy();

    @Test
    void compare_sentinel_isIntegerMaxValue() {
        // 接口契约：无法转换时返回 Integer.MAX_VALUE
        assertThat(strategy.compare(null, 1)).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void compare_sentinel_notAValidComparisonResult() {
        // 哨兵值与所有合法 compare 结果（负/零/正）都不同，调用方可安全识别
        int sentinel = strategy.compare(Double.NaN, 1.0);
        assertThat(sentinel).isEqualTo(Integer.MAX_VALUE);
    }
}
