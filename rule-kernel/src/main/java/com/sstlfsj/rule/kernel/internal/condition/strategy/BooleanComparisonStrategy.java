package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 布尔比较策略：支持 Boolean 对象及字符串形式（"true"/"false"）的相等判定。
 * 布尔值无序，compare 方法不支持，调用时抛 {@link UnsupportedOperationException}。
 * 适用于 dataType=BOOLEAN。
 */
class BooleanComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        throw new UnsupportedOperationException("BOOLEAN 类型不支持排序比较（compare）");
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return toBoolean(actual) == toBoolean(operand);
    }

    /** 将 Object 转为基础类型 boolean：Boolean 直取，String 走 Boolean.parseBoolean，其余为 false。 */
    private static boolean toBoolean(Object o) {
        if (o instanceof Boolean b) return b;
        if (o instanceof String s) return Boolean.parseBoolean(s);
        return false;
    }
}
