package com.sstlfsj.rule.web.api.dto;

import java.time.Instant;
import java.util.Map;

/**
 * HTTP 评估请求体；source 由 controller 权威设为 HTTP，不接收外部传入的 source。
 * tenantCode 为租户业务标识（{@code tenant.code}），controller 边界解析为内部 id。
 */
public record EvalEventRequest(
        String tenantCode,
        String sceneCode,
        String eventType,
        String subjectId,
        String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Map<String, Object> providedMetrics
) {}
