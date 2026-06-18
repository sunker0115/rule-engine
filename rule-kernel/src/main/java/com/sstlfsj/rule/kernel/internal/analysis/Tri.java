package com.sstlfsj.rule.kernel.internal.analysis;

/**
 * 三值逻辑。用于规则集静态分析中无法精确判定时的保守降级。
 *
 * <p>{@link #UNKNOWN} 表示因取值不可静态求解（如正则、非数值点集对数值区间）而无法精确判定，
 * 调用方应据此保守处理（既不报相交也不报互斥）。
 */
public enum Tri {
    TRUE,
    FALSE,
    UNKNOWN
}
