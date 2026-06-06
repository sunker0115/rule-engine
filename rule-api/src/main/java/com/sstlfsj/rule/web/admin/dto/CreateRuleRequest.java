package com.sstlfsj.rule.web.admin.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建规则草稿请求体，对应 10-api-contract.md §4.1。 */
public record CreateRuleRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String code,
        @NotBlank String name,
        String kind,
        Object conditionAst,
        Object decisionBindings,
        Object preGates,
        Object triggerEventTypes
) {}
