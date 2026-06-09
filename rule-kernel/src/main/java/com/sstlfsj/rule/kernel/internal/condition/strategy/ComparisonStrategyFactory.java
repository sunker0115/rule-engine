package com.sstlfsj.rule.kernel.internal.condition.strategy;

import com.sstlfsj.rule.kernel.api.model.DataType;

/**
 * 比较策略工厂：按 dataType 返回缓存单例，零额外分配。
 * LONG -> Long（整型原始比较）；DOUBLE -> Double（浮点原始比较，NaN/∞ 不命中）；DECIMAL -> Decimal（BigDecimal 精确）；
 * STRING -> String；BOOLEAN -> Boolean；DATE -> Date；DATETIME -> DateTime；null/LIST/UNKNOWN/其他未知 -> Default。
 */
public final class ComparisonStrategyFactory {

    // 策略单例字段加 _STRATEGY 后缀，避免与 DataType 枚举常量名（LONG/DOUBLE/...）在 switch 中混淆
    private static final LongComparisonStrategy     LONG_STRATEGY     = new LongComparisonStrategy();
    private static final DoubleComparisonStrategy   DOUBLE_STRATEGY   = new DoubleComparisonStrategy();
    private static final DecimalComparisonStrategy  NUMERIC_STRATEGY  = new DecimalComparisonStrategy();
    private static final StringComparisonStrategy   STRING_STRATEGY   = new StringComparisonStrategy();
    private static final BooleanComparisonStrategy  BOOLEAN_STRATEGY  = new BooleanComparisonStrategy();
    private static final DateComparisonStrategy     DATE_STRATEGY     = new DateComparisonStrategy();
    private static final DateTimeComparisonStrategy DATETIME_STRATEGY = new DateTimeComparisonStrategy();
    private static final DefaultComparisonStrategy  DEFAULT_STRATEGY  = new DefaultComparisonStrategy();

    private ComparisonStrategyFactory() {}

    /**
     * 根据 metric 的 dataType 返回对应策略单例。
     *
     * @param dataType metric_definition.data_type 的值（LONG/DOUBLE/DECIMAL/STRING/BOOLEAN/LIST/DATE/DATETIME/null）
     * @return 对应的 ComparisonStrategy 单例
     */
    public static ComparisonStrategy forType(String dataType) {
        // null/未识别 → UNKNOWN，连同 LIST 一并落到 DEFAULT_STRATEGY，语义同原 null/default 分支
        return switch (DataType.fromTag(dataType)) {
            case LONG     -> LONG_STRATEGY;
            case DOUBLE   -> DOUBLE_STRATEGY;
            case DECIMAL  -> NUMERIC_STRATEGY;
            case STRING   -> STRING_STRATEGY;
            case BOOLEAN  -> BOOLEAN_STRATEGY;
            case DATE     -> DATE_STRATEGY;
            case DATETIME -> DATETIME_STRATEGY;
            case LIST, UNKNOWN -> DEFAULT_STRATEGY;
        };
    }
}
