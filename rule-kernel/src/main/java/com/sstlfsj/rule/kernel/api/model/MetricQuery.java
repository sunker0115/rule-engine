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
        Instant now,
        /** 主体属性（来自 Subject.attributes，开放异构）；供 SQL/HTTP 的 subject.* 绑定。 */
        Map<String, Object> subjectAttributes
) {
    /**
     * 兼容旧调用点的便利构造：无主体属性，subjectAttributes 默认空 map。
     *
     * @param metricCode   指标编码
     * @param tenantId     租户 id
     * @param subjectId    主体 id
     * @param params       取数参数
     * @param eventPayload 事件 payload
     * @param now          引擎统一时钟
     */
    public MetricQuery(String metricCode, String tenantId, String subjectId,
                       Map<String, Object> params, Map<String, Object> eventPayload, Instant now) {
        this(metricCode, tenantId, subjectId, params, eventPayload, now, Map.of());
    }
}
