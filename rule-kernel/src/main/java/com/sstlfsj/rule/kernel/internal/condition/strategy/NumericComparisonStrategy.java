package com.sstlfsj.rule.kernel.internal.condition.strategy;

import java.math.BigDecimal;

/**
 * 数值比较策略：内核使用 BigDecimal，避免 double 精度丢失。
 * 适用于 dataType=LONG 和 dataType=DOUBLE。
 * null、NaN、Infinity 无法转换时：compare 返回哨兵值 Integer.MAX_VALUE，equals 返回 false。
 */
class NumericComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        BigDecimal a = toBigDecimal(actual);
        BigDecimal b = toBigDecimal(operand);
        if (a == null || b == null) return Integer.MAX_VALUE;
        return a.compareTo(b);
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        BigDecimal a = toBigDecimal(actual);
        BigDecimal b = toBigDecimal(operand);
        if (a == null || b == null) return false;
        // compareTo==0 忽略 scale（50000.00 == 50000），不用 BigDecimal.equals
        return a.compareTo(b) == 0;
    }

    /**
     * 将 Object 转为 BigDecimal：Number 走 toString 路径（保留精度），
     * String 直接 new BigDecimal(s)；NaN/Infinity 的 toString 无法解析 -> 返回 null。
     */
    static BigDecimal toBigDecimal(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) {
            try {
                return new BigDecimal(n.toString());
            } catch (NumberFormatException e) {
                // Double.NaN / Infinity 的 toString 不可解析
                return null;
            }
        }
        if (o instanceof String s) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
