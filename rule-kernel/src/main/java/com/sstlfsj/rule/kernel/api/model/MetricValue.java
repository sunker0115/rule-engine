package com.sstlfsj.rule.kernel.api.model;

/**
 * 单个指标的取数结果。
 * <p>{@code valueSource} 标记来源（PROVIDED / FETCHED）；{@code errorCode} 非 null 表示取数失败降级，
 * 此时 {@code value} 通常为 null，引用该指标的条件节点应不命中（satisfied=false）。</p>
 */
public record MetricValue(
        Object value,
        String dataType,
        String valueSource,
        String errorCode
) {
    /**
     * 兼容旧调用点的便利构造：无错误的成功结果，errorCode 默认 null。
     *
     * @param value       指标值
     * @param dataType    数据类型
     * @param valueSource 来源（PROVIDED / FETCHED）
     */
    public MetricValue(Object value, String dataType, String valueSource) {
        this(value, dataType, valueSource, null);
    }

    /**
     * 构造取数失败的降级结果（value=null，valueSource=FETCHED）。
     *
     * @param errorCode 失败错误码（如 METRIC_FETCH_FAIL）
     * @return 标记 isError 的 MetricValue
     */
    public static MetricValue error(String errorCode) {
        return new MetricValue(null, DataType.UNKNOWN.tag(), ValueSource.FETCHED.tag(), errorCode);
    }

    /** @return 是否为取数失败的降级值。 */
    public boolean isError() {
        return errorCode != null;
    }
}
