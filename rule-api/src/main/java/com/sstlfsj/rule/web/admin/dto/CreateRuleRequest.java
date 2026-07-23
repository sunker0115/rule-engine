package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 创建规则草稿请求体，对应 10-api-contract.md §4.1。body 为多态载体（AstBody/ScriptBody/FlowBody，与 kind 一致）。 */
public record CreateRuleRequest(
        @NotNull Long tenantId,
        @NotBlank String sceneCode,
        @NotBlank String code,
        @NotBlank String name,
        String kind,
        RuleBody body,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes
) implements RuleContentSource {}
