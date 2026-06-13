package com.sstlfsj.rule.observability.api.metrics;

/** Prometheus 指标名常量，统一管理所有规则引擎上报的 metric 名称。 */
public final class RuleMetrics {
    private RuleMetrics() {}

    // 评估指标
    public static final String EVAL_DURATION_SECONDS   = "rule_eval_duration_seconds";
    public static final String EVAL_TOTAL              = "rule_eval_total";
    public static final String EVAL_ERROR_TOTAL        = "rule_eval_error_total";

    // metric 预拉
    public static final String METRIC_FETCH_DURATION   = "rule_metric_fetch_duration_seconds";
    public static final String METRIC_CACHE_HIT_TOTAL  = "rule_metric_cache_hit_total";
    public static final String METRIC_CACHE_MISS_TOTAL = "rule_metric_cache_miss_total";

    // TraceWriter 队列
    public static final String TRACE_QUEUE_SIZE        = "rule_trace_queue_size";
    public static final String TRACE_WRITE_BATCH_TOTAL = "rule_trace_write_batch_total";
}
