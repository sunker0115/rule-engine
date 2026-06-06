package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 字符串比较策略：两侧均 String.valueOf 后按字典序比较。
 * 适用于 dataType=STRING。
 * 入参为 null 时 String.valueOf 会得到字面量 "null"，调用方应在调用前过滤 null 值。
 */
class StringComparisonStrategy implements ComparisonStrategy {

    @Override
    public int compare(Object actual, Object operand) {
        return String.valueOf(actual).compareTo(String.valueOf(operand));
    }

    @Override
    public boolean equals(Object actual, Object operand) {
        return String.valueOf(actual).equals(String.valueOf(operand));
    }
}
