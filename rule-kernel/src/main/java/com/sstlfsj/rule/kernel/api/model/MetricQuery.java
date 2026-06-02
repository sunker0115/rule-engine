package com.sstlfsj.rule.kernel.api.model;

import java.util.Map;

/** MetricSourceHandler.fetch() 的入参，描述一次指标取数请求。 */
public record MetricQuery(
        String metricCode,
        String tenantId,
        String subjectId,
        Map<String, Object> params,
        Map<String, Object> eventPayload
) {}
