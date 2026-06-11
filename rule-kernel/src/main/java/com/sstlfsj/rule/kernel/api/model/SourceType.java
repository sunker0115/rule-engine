package com.sstlfsj.rule.kernel.api.model;

/** 指标取数源类型(契约值,= DB metric_definition.source_type;作 @MetricSourceType 注解值,故为编译期 String 常量而非枚举)。 */
public final class SourceType {
    private SourceType() {}

    /** 主体属性直取。 */
    public static final String ATTRIBUTE = "ATTRIBUTE";
    /** SQL 聚合查询。 */
    public static final String SQL_AGGREGATE = "SQL_AGGREGATE";
    /** 外部 HTTP 取数。 */
    public static final String EXTERNAL_HTTP = "EXTERNAL_HTTP";
    /** 流式取数。 */
    public static final String STREAM = "STREAM";

    /** 全部合法源类型(供 MetricEnums.SOURCE_TYPES 派生)。 */
    public static final java.util.Set<String> ALL =
            java.util.Set.of(ATTRIBUTE, SQL_AGGREGATE, EXTERNAL_HTTP, STREAM);
}
