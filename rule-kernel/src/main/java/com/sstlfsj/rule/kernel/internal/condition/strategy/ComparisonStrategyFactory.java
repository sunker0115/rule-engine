package com.sstlfsj.rule.kernel.internal.condition.strategy;

/**
 * 比较策略工厂：按 dataType 返回缓存单例，零额外分配。
 * LONG -> Long（整型原始比较）；DOUBLE -> Double（浮点原始比较，NaN/∞ 不命中）；DECIMAL -> Decimal（BigDecimal 精确）；
 * STRING -> String；BOOLEAN -> Boolean；DATE -> Date；DATETIME -> DateTime；null/LIST/UNKNOWN/其他未知 -> Default。
 */
public final class ComparisonStrategyFactory {

    private static final LongComparisonStrategy     LONG      = new LongComparisonStrategy();
    private static final DoubleComparisonStrategy   DOUBLE    = new DoubleComparisonStrategy();
    private static final DecimalComparisonStrategy  NUMERIC   = new DecimalComparisonStrategy();
    private static final StringComparisonStrategy   STRING    = new StringComparisonStrategy();
    private static final BooleanComparisonStrategy  BOOLEAN   = new BooleanComparisonStrategy();
    private static final DateComparisonStrategy     DATE      = new DateComparisonStrategy();
    private static final DateTimeComparisonStrategy DATETIME  = new DateTimeComparisonStrategy();
    private static final DefaultComparisonStrategy  DEFAULT   = new DefaultComparisonStrategy();

    private ComparisonStrategyFactory() {}

    /**
     * 根据 metric 的 dataType 返回对应策略单例。
     *
     * @param dataType metric_definition.data_type 的值（LONG/DOUBLE/DECIMAL/STRING/BOOLEAN/LIST/DATE/DATETIME/null）
     * @return 对应的 ComparisonStrategy 单例
     */
    public static ComparisonStrategy forType(String dataType) {
        if (dataType == null) return DEFAULT;
        return switch (dataType) {
            case "LONG"            -> LONG;
            case "DOUBLE"          -> DOUBLE;
            case "DECIMAL"         -> NUMERIC;
            case "STRING"          -> STRING;
            case "BOOLEAN"         -> BOOLEAN;
            case "DATE"            -> DATE;
            case "DATETIME"        -> DATETIME;
            default                -> DEFAULT;  // LIST/UNKNOWN/其他未知
        };
    }
}
