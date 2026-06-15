package com.sstlfsj.rule.kernel.api.model;

/**
 * 取数失败细码（跨源，可观测用）。降级行为不变——引用该 metric 的条件仍不命中（D15），
 * 细码只进 MetricValue.errorCode + 指标标签。EvalErrorCode.METRIC_FETCH_FAIL 仍为语义层伞码。
 */
public enum MetricFetchError {
    NOT_FOUND, TIMEOUT, UNAUTHORIZED, UPSTREAM_ERROR, PARSE_ERROR, MAPPING_ERROR, TYPE_MISMATCH;

    /** @return 落 MetricValue.errorCode 的字符串标签（== 枚举名）。 */
    public String tag() {
        return name();
    }
}
