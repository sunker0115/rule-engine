package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 默认比较策略：dataType 未声明（null）时，按 actual 运行时类型分派：
 * Number（含 BigDecimal）→数值策略、Boolean→布尔策略、其余→字符串策略。
 * 适用于 DSL 构造的节点（dataType=null）及 LIST/UNKNOWN dataType。
 */
class DefaultComparisonStrategy implements ComparisonStrategy {

    private static final NumericComparisonStrategy NUMERIC = new NumericComparisonStrategy();
    private static final StringComparisonStrategy STRING = new StringComparisonStrategy();
    private static final BooleanComparisonStrategy BOOLEAN = new BooleanComparisonStrategy();

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof Number) {
            return NUMERIC.compare(actual, operand);
        }
        if (actual instanceof Boolean) {
            return BOOLEAN.compare(actual, operand); // 抛 UnsupportedOperationException
        }
        return STRING.compare(actual, operand);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        if (actual instanceof Number) {
            return NUMERIC.equals(actual, operand);
        }
        if (actual instanceof Boolean) {
            return BOOLEAN.equals(actual, operand);
        }
        return STRING.equals(actual, operand);
    }
}
