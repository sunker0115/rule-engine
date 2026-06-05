package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 类型化比较策略接口。
 * 实现类针对特定 dataType 执行精确比较，策略无状态、可共享单例。
 */
public interface ComparisonStrategy {

    /**
     * 对 actual 与 operand 进行排序比较，返回负数/零/正数（约定同 Comparable）。
     * 不支持排序的类型（如 Boolean）抛 {@link UnsupportedOperationException}。
     *
     * @param actual  指标实际值
     * @param operand 条件操作数
     * @return 负数表示 actual < operand，0 表示相等，正数表示 actual > operand
     */
    int compare(Object actual, Object operand);

    /**
     * 判断 actual 与 operand 是否相等（类型语义下的相等）。
     *
     * @param actual  指标实际值
     * @param operand 条件操作数
     * @return 相等返回 true
     */
    boolean equals(Object actual, Object operand);
}
