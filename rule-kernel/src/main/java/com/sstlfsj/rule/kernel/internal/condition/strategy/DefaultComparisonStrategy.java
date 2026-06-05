package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 默认比较策略：dataType 未声明（null）时，按 actual 运行时 Java 类型推断。
 * 推断顺序：BigDecimal -> Number -> Boolean -> String（顺序敏感，不可调换）。
 * 适用于 DSL 构造的节点（dataType=null）及 LIST/UNKNOWN dataType。
 */
class DefaultComparisonStrategy implements ComparisonStrategy {

    private static final NumericComparisonStrategy NUMERIC = new NumericComparisonStrategy();
    private static final StringComparisonStrategy STRING = new StringComparisonStrategy();
    private static final BooleanComparisonStrategy BOOLEAN = new BooleanComparisonStrategy();

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof java.math.BigDecimal || actual instanceof Number) {
            return NUMERIC.compare(actual, operand);
        }
        if (actual instanceof Boolean) {
            return BOOLEAN.compare(actual, operand); // 抛 UnsupportedOperationException
        }
        return STRING.compare(actual, operand);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        if (actual instanceof java.math.BigDecimal || actual instanceof Number) {
            return NUMERIC.equals(actual, operand);
        }
        if (actual instanceof Boolean) {
            return BOOLEAN.equals(actual, operand);
        }
        return STRING.equals(actual, operand);
    }
}
