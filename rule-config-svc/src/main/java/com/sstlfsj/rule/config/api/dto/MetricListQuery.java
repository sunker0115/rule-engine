package com.sstlfsj.rule.config.api.dto;

import java.util.List;

/**
 * Metric 列表查询条件。
 *
 * @param tenantId 租户 ID（必填）
 * @param scenes   场景编码列表（选填，v1 暂不按场景白名单过滤）
 */
public record MetricListQuery(Long tenantId, List<String> scenes) {}
