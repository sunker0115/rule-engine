package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 创建规则草稿请求体，对应 10-api-contract.md §4.1。EXPRESSION_SCRIPT 时 conditionAst 传 null、script 非空。 */
public record CreateRuleRequest(
        @NotBlank String tenantId,
        @NotBlank String sceneCode,
        @NotBlank String code,
        @NotBlank String name,
        String kind,
        AstNode conditionAst,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes,
        ScriptSource script
) {}
