package com.sstlfsj.rule.web.api.dto;

import java.time.Instant;
import java.util.Map;

/** HTTP 评估请求体；source 由 controller 权威设为 HTTP，不接收外部传入的 source。 */
public record EvalEventRequest(
        String tenantId,
        String sceneCode,
        String eventType,
        String subjectId,
        String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Map<String, Object> providedMetrics
) {}
