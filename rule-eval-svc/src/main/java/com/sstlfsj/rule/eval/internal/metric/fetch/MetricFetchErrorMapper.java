package com.sstlfsj.rule.eval.internal.metric.fetch;

import com.sstlfsj.rule.kernel.api.model.MetricFetchError;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;

/** 把取数异常/状态归一到 MetricFetchError（共用脊·调用无关）。降级行为不变，仅细化可观测码。 */
public class MetricFetchErrorMapper {

    /** 异常 → 细码。 */
    public MetricFetchError fromException(Throwable t) {
        if (t instanceof HttpTimeoutException || t instanceof TimeoutException) return MetricFetchError.TIMEOUT;
        return MetricFetchError.UPSTREAM_ERROR;
    }

    /** HTTP 状态 → 细码（401/403→UNAUTHORIZED，其余非 2xx→UPSTREAM_ERROR）。 */
    public MetricFetchError fromHttpStatus(int status) {
        if (status == 401 || status == 403) return MetricFetchError.UNAUTHORIZED;
        return MetricFetchError.UPSTREAM_ERROR;
    }
}
