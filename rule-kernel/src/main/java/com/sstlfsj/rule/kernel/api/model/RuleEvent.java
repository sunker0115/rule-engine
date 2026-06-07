package com.sstlfsj.rule.kernel.api.model;

import lombok.Builder;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 触发规则评估的业务事件，eventId 用于幂等校验；source 为渠道（由注入入口权威设置）。 */
@Builder
public record RuleEvent(
        String tenantId,
        String sceneCode,
        String eventType,
        String subjectId,
        String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Map<String, Object> providedMetrics,
        EventSource source
) {
    public RuleEvent {
        Objects.requireNonNull(source, "RuleEvent.source 不能为空");
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
        payload = payload == null ? Map.of() : Map.copyOf(payload);
        providedMetrics = providedMetrics == null ? Map.of() : Map.copyOf(providedMetrics);
    }
}
