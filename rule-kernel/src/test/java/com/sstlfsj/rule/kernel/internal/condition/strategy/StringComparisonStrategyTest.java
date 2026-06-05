package com.sstlfsj.rule.kernel.internal.condition.strategy;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StringComparisonStrategyTest {

    private final StringComparisonStrategy strategy = new StringComparisonStrategy();

    @Test
    void equals_sameString_returnsTrue() {
        assertThat(strategy.equals("ACTIVE", "ACTIVE")).isTrue();
    }

    @Test
    void equals_differentString_returnsFalse() {
        assertThat(strategy.equals("ACTIVE", "INACTIVE")).isFalse();
    }

    @Test
    void equals_zero100_notEqualTo_100() {
        // STRING 类型："0100" 不等于 "100"（关键 bug 修复验证）
        assertThat(strategy.equals("0100", "100")).isFalse();
    }

    @Test
    void compare_lexicographicOrder() {
        // "abc" < "abd"
        assertThat(strategy.compare("abc", "abd")).isLessThan(0);
        assertThat(strategy.compare("abd", "abc")).isGreaterThan(0);
        assertThat(strategy.compare("abc", "abc")).isEqualTo(0);
    }

    @Test
    void compare_numericStringLexicographic() {
        // 字符串路径下 "100" vs "99"：字典序 "1" < "9"，所以 "100" < "99"
        assertThat(strategy.compare("100", "99")).isLessThan(0);
    }

    @Test
    void nullActual_treatedAsLiteralNullString() {
        // Javadoc 契约：null 经 String.valueOf 变为字面量 "null"，调用方应在调用前过滤
        assertThat(strategy.equals(null, "null")).isTrue();
        assertThat(strategy.equals(null, "ACTIVE")).isFalse();
    }
}
