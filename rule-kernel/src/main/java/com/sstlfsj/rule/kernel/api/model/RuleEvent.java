package com.sstlfsj.rule.kernel.api.model;

import java.time.Instant;
import java.util.Map;

/** 触发规则评估的业务事件，eventId 用于幂等校验。 */
public record RuleEvent(
        String tenantId,
        String sceneCode,
        String eventType,
        String subjectId,
        String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Map<String, Object> providedMetrics
) {
    public RuleEvent {
        payload = Map.copyOf(payload);
        providedMetrics = providedMetrics == null ? Map.of() : Map.copyOf(providedMetrics);
    }
}
