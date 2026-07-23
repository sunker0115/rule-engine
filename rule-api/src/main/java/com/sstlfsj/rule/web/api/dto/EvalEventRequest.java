package com.sstlfsj.rule.web.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.Map;

/**
 * HTTP 评估请求体；source 由 controller 权威设为 HTTP，不接收外部传入的 source。
 * tenantCode 为租户业务标识（{@code tenant.code}），controller 边界解析为内部 id。
 * asOf 为可选的求值时钟（ISO-8601 Instant）；缺省 null 时引擎用 {@code Instant.now()}。
 */
public record EvalEventRequest(
        @NotBlank String tenantCode,
        @NotBlank String sceneCode,
        @NotBlank String eventType,
        @NotBlank String subjectId,
        @NotBlank String eventId,
        Instant occurredAt,
        Map<String, Object> payload,
        Instant asOf
) {}
