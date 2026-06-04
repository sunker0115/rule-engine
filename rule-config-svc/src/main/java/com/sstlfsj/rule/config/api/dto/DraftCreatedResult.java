package com.sstlfsj.rule.config.api.dto;

/**
 * 创建规则草稿响应，对应 10-api-contract.md §4.1 Response 201。
 *
 * @param ruleDefinitionId 新建的规则定义 ID
 * @param ruleVersionId    新建的规则版本 ID
 * @param version          版本号（草稿固定为 1）
 * @param status           状态（固定为 DRAFT）
 */
public record DraftCreatedResult(
        Long ruleDefinitionId,
        Long ruleVersionId,
        Long version,
        String status
) {}
