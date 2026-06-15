package com.sstlfsj.rule.eval.internal.metric.fetch;

/**
 * 标记 5xx 响应可重试的内部异常：仅在 handler 发请求 callable 与 {@link ResiliencePolicyExecutor}
 * 之间流转，承载上游状态码以便重试耗尽后归一为细码。绝不外泄到引擎（由 handler catch 归一）。
 */
public class RetryableUpstreamStatusException extends RuntimeException {

    private final int status;

    /**
     * @param status 触发重试的上游 HTTP 状态码（5xx）
     */
    public RetryableUpstreamStatusException(int status) {
        super("upstream status " + status);
        this.status = status;
    }

    /** @return 上游 HTTP 状态码。 */
    public int status() {
        return status;
    }
}
