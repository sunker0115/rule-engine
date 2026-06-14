package com.sstlfsj.rule.config.api.dto;

import java.time.LocalDateTime;

/**
 * 规则列表查询响应项，对应 10-api-contract.md §4.4。
 *
 * @param tenantId         租户 ID
 * @param ruleDefinitionId 规则定义 ID
 * @param code             规则编码
 * @param name             规则名称
 * @param kind             规则类型（AST_BOOLEAN / SCORECARD 等）
 * @param sceneCode        所属场景编码
 * @param status           规则状态（DRAFT / PUBLISHED / DISABLED）
 * @param currentVersion   当前版本 ID（未发布时为 null）
 * @param publishedAt      最后发布时间（未发布时为 null）
 * @param createdAt        创建时间
 */
public record RuleListItemVO(
        String tenantId,
        Long ruleDefinitionId,
        String code,
        String name,
        String kind,
        String sceneCode,
        String status,
        Long currentVersion,
        LocalDateTime publishedAt,
        LocalDateTime createdAt
) {}
