package com.sstlfsj.rule.kernel.api.model;

import java.time.Instant;
import java.util.Map;

/** MetricSourceHandler.fetch() 的入参，描述一次指标取数请求。 */
public record MetricQuery(
        String metricCode,
        String tenantId,
        String subjectId,
        Map<String, Object> params,
        Map<String, Object> eventPayload,
        /** 引擎统一时钟，来自 EvalContext.now；SQL 的 :now 即取此字段（非 DB NOW()），保 dry-run 可重放。 */
        Instant now
) {}
