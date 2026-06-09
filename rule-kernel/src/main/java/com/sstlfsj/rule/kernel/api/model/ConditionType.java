package com.sstlfsj.rule.kernel.api.model;

/** 内置条件算子的 conditionType 码(契约值,= AST ConditionNode.conditionType / evaluator 注册键)。
 *  算子是开放集——自定义 ConditionEvaluator 可注册任意 conditionType,故此处仅集中内置算子为常量,非闭合枚举。 */
public final class ConditionType {
    private ConditionType() {}

    /** 等于。 */
    public static final String EQ = "EQ";
    /** 不等于。 */
    public static final String NEQ = "NEQ";
    /** 大于。 */
    public static final String GT = "GT";
    /** 大于等于。 */
    public static final String GTE = "GTE";
    /** 小于。 */
    public static final String LT = "LT";
    /** 小于等于。 */
    public static final String LTE = "LTE";
    /** 在集合内。 */
    public static final String IN = "IN";
    /** 不在集合内。 */
    public static final String NOT_IN = "NOT_IN";
    /** 在区间内(闭区间)。 */
    public static final String BETWEEN = "BETWEEN";
    /** 不在区间内。 */
    public static final String NOT_BETWEEN = "NOT_BETWEEN";
    /** 列表包含。 */
    public static final String CONTAINS = "CONTAINS";
    /** 列表不包含。 */
    public static final String NOT_CONTAINS = "NOT_CONTAINS";
    /** 字符串前缀匹配。 */
    public static final String STARTS_WITH = "STARTS_WITH";
    /** 字符串后缀匹配。 */
    public static final String ENDS_WITH = "ENDS_WITH";
    /** 正则匹配。 */
    public static final String MATCHES = "MATCHES";
    /** 日期早于。 */
    public static final String DATE_BEFORE = "DATE_BEFORE";
    /** 日期晚于。 */
    public static final String DATE_AFTER = "DATE_AFTER";
    /** 时间窗口内。 */
    public static final String TIME_WINDOW = "time.window";
    /** 事件发生时间区间比较。 */
    public static final String TIME_OCCURRED_AT = "time.occurred_at";
}
