package com.sstlfsj.rule.kernel.api.model;

/**
 * 内置条件算子的 {@code ConditionNode.params} 键常量，单一真相源。
 * 生产端（DSL/SDK builder）与消费端（ConditionEvaluator 实现）均引用此处，
 * 从结构上杜绝两端键字面量分歧（曾因两端各写裸字符串导致规则永不命中）。
 * conditionType 是 SPI 开放集，故自定义算子的键由各自实现定义，不在此枚举。
 */
public final class ConditionParams {

    private ConditionParams() {}

    /** 比较算子（EQ/NEQ/GT/GTE/LT/LTE）的比较操作数。 */
    public static final String THRESHOLD = "threshold";
    /** BETWEEN/NOT_BETWEEN 下界。 */
    public static final String MIN = "min";
    /** BETWEEN/NOT_BETWEEN 上界。 */
    public static final String MAX = "max";
    /** IN/NOT_IN 候选集合。 */
    public static final String VALUES = "values";
    /** CONTAINS/NOT_CONTAINS 待检元素。 */
    public static final String ELEMENT = "element";
    /** STARTS_WITH 前缀。 */
    public static final String PREFIX = "prefix";
    /** ENDS_WITH 后缀。 */
    public static final String SUFFIX = "suffix";
    /** MATCHES 正则。 */
    public static final String REGEX = "regex";
    /** 时区（日期/时间类比较，可选）。 */
    public static final String TIMEZONE = "timezone";
    /** time.window / time.occurred_at 开始时间(HH:mm 或 ISO-8601)。 */
    public static final String START         = "start";
    /** time.window / time.occurred_at 结束时间(HH:mm 或 ISO-8601)。 */
    public static final String END           = "end";
    /** time.window 排除日期列表(MM-DD 字符串数组,可选)。 */
    public static final String DATES_EXCLUDE = "datesExclude";
    /** time.window 生效星期(MON/TUE/... 字符串数组,可选)。 */
    public static final String DAYS_OF_WEEK  = "daysOfWeek";
    /** time.occurred_at 比较运算符(BEFORE / AFTER / BETWEEN)。 */
    public static final String OPERATOR      = "operator";
    /** time.occurred_at 单端比较目标值(ISO-8601 或 $now)。 */
    public static final String VALUE         = "value";
}
