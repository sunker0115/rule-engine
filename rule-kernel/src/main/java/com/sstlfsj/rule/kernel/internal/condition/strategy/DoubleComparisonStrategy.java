package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.math.BigDecimal;

/** DOUBLE 比较:Double.compare(零分配);NaN/∞ 显式哨兵不命中(不用 Double.compare 的 NaN-最大全序);BigDecimal 操作数回退 Decimal。 */
class DoubleComparisonStrategy implements ComparisonStrategy {

    private static final DecimalComparisonStrategy FALLBACK = new DecimalComparisonStrategy();

    @Override
    public int compare(Object actual, Object operand) {
        if (actual instanceof BigDecimal || operand instanceof BigDecimal) return FALLBACK.compare(actual, operand);
        Double a = toDouble(actual);
        Double b = toDouble(operand);
        if (a == null || b == null) return Integer.MAX_VALUE;
        if (a.isNaN() || a.isInfinite() || b.isNaN() || b.isInfinite()) return Integer.MAX_VALUE;
        return Double.compare(a, b);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        if (actual instanceof BigDecimal || operand instanceof BigDecimal) return FALLBACK.equals(actual, operand);
        Double a = toDouble(actual);
        Double b = toDouble(operand);
        if (a == null || b == null) return false;
        if (a.isNaN() || a.isInfinite() || b.isNaN() || b.isInfinite()) return false;
        return a.doubleValue() == b.doubleValue();
    }

    /** Number → doubleValue;String → parseDouble;否则 null。 */
    private static Double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String str) {
            try { return Double.parseDouble(str.trim()); } catch (NumberFormatException e) { return null; }
        }
        return null;
    }
}
