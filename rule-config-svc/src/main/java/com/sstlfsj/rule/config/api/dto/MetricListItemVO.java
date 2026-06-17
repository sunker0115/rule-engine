package com.sstlfsj.rule.config.api.dto;

/**
 * Metric 管理列表项——仅在 admin 接口使用，不侵入 kernel MetricDescriptor。
 */
public record MetricListItemVO(
        String metricCode,
        int metricVersion,
        String sourceType,
        String dataType,
        boolean allowProvided,
        int cacheTtlSeconds,
        java.util.Map<String, Object> params,
        String name,
        String status,
        Long tenantId,
        java.time.LocalDateTime createdAt,
        java.time.LocalDateTime updatedAt
) {}
