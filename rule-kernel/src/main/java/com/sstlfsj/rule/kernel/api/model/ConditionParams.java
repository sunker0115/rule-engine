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
}
