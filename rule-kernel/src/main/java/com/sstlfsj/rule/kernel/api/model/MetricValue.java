package com.sstlfsj.rule.kernel.api.model;

/**
 * 单个指标的取数结果。
 * <p>{@code valueSource} 标记来源（PROVIDED / FETCHED）；{@code errorCode} 非 null 表示取数失败降级，
 * 此时 {@code value} 通常为 null，引用该指标的条件节点应不命中（satisfied=false）。
 * {@code reason} 为可观测的失败原因明细（可空），不影响降级行为，仅供 trace / 日志。</p>
 */
public record MetricValue(
        Object value,
        String dataType,
        String valueSource,
        String errorCode,
        String reason
) {
    /**
     * 兼容旧调用点的便利构造：带错误码、无 reason 明细。
     *
     * @param value       指标值
     * @param dataType    数据类型
     * @param valueSource 来源（PROVIDED / FETCHED）
     * @param errorCode   失败错误码，null 表示成功
     */
    public MetricValue(Object value, String dataType, String valueSource, String errorCode) {
        this(value, dataType, valueSource, errorCode, null);
    }

    /**
     * 兼容旧调用点的便利构造：无错误的成功结果，errorCode 默认 null。
     *
     * @param value       指标值
     * @param dataType    数据类型
     * @param valueSource 来源（PROVIDED / FETCHED）
     */
    public MetricValue(Object value, String dataType, String valueSource) {
        this(value, dataType, valueSource, null, null);
    }

    /**
     * 构造取数失败的降级结果（value=null，valueSource=FETCHED）。
     * provider 开放码经此原样穿透（errorCode 字段保持 String）。
     *
     * @param errorCode 失败错误码（如 METRIC_FETCH_FAIL）
     * @return 标记 isError 的 MetricValue
     */
    public static MetricValue error(String errorCode) {
        return new MetricValue(null, DataType.UNKNOWN.tag(), ValueSource.FETCHED.tag(), errorCode, null);
    }

    /**
     * 构造取数失败的降级结果，使用规范错误码。errorCode 以 {@link EvalErrorCode#name()} 落 String。
     *
     * @param errorCode 规范错误码
     * @return 标记 isError 的 MetricValue
     */
    public static MetricValue error(EvalErrorCode errorCode) {
        return error(errorCode.name());
    }

    /** @return 是否为取数失败的降级值。 */
    public boolean isError() {
        return errorCode != null;
    }
}
