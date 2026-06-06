package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 比较策略工厂：按 dataType 返回缓存单例，零额外分配。
 * LONG/DOUBLE -> Numeric；STRING -> String；BOOLEAN -> Boolean；DATE -> Date；DATETIME -> DateTime；
 * null/LIST/UNKNOWN/其他未知 -> Default。
 */
public final class ComparisonStrategyFactory {

    private static final NumericComparisonStrategy  NUMERIC   = new NumericComparisonStrategy();
    private static final StringComparisonStrategy   STRING    = new StringComparisonStrategy();
    private static final BooleanComparisonStrategy  BOOLEAN   = new BooleanComparisonStrategy();
    private static final DateComparisonStrategy     DATE      = new DateComparisonStrategy();
    private static final DateTimeComparisonStrategy DATETIME  = new DateTimeComparisonStrategy();
    private static final DefaultComparisonStrategy  DEFAULT   = new DefaultComparisonStrategy();

    private ComparisonStrategyFactory() {}

    /**
     * 根据 metric 的 dataType 返回对应策略单例。
     *
     * @param dataType metric_definition.data_type 的值（LONG/DOUBLE/STRING/BOOLEAN/LIST/null）
     * @return 对应的 ComparisonStrategy 单例
     */
    public static ComparisonStrategy forType(String dataType) {
        if (dataType == null) return DEFAULT;
        return switch (dataType) {
            case "LONG", "DOUBLE"  -> NUMERIC;
            case "STRING"          -> STRING;
            case "BOOLEAN"         -> BOOLEAN;
            case "DATE"            -> DATE;
            case "DATETIME"        -> DATETIME;
            default                -> DEFAULT;  // LIST/UNKNOWN/其他未知
        };
    }
}
