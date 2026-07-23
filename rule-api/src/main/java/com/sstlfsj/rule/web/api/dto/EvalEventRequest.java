package com.sstlfsj.rule.web.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * HTTP 评估请求体；source 由 controller 权威设为 HTTP，不接收外部传入的 source。
 * tenantCode 为租户业务标识（{@code tenant.code}），controller 边界解析为内部 id。
 * asOf 为可选的求值时钟（ISO-8601 Instant）；缺省 null 时引擎用 {@code Instant.now()}。
 * providedMetrics 为可选预提供指标（仅 ATTRIBUTE allowProvided=true 场景使用），缺省不注入。
 */
public record EvalEventRequest(
        @NotBlank String tenantCode,
        @NotBlank String sceneCode,
        @NotBlank String eventType,
        @NotBlank String subjectId,
        @NotBlank String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Instant asOf,
        Map<String, Object> providedMetrics
) {
    /** 兼容旧调用方：providedMetrics 默认空 map。 */
    public EvalEventRequest(String tenantCode, String sceneCode, String eventType,
                             String subjectId, String eventId,
                             Instant occurredAt, Map<String, Object> payload, Instant asOf) {
        this(tenantCode, sceneCode, eventType, subjectId, eventId, occurredAt, payload, asOf, Map.of());
    }

    public EvalEventRequest {
        providedMetrics = providedMetrics == null ? Map.of() : providedMetrics;
    }
}
