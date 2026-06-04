package com.sstlfsj.rule.config.api.dto;

import java.time.LocalDateTime;

/**
 * 规则列表查询响应项，对应 10-api-contract.md §4.4。
 *
 * @param ruleDefinitionId 规则定义 ID
 * @param code             规则编码
 * @param name             规则名称
 * @param status           规则状态（DRAFT / PUBLISHED / DISABLED）
 * @param currentVersion   当前版本 ID（未发布时为 null）
 * @param publishedAt      最后发布时间（未发布时为 null）
 */
public record RuleListItemVO(
        Long ruleDefinitionId,
        String code,
        String name,
        String status,
        Long currentVersion,
        LocalDateTime publishedAt
) {}
