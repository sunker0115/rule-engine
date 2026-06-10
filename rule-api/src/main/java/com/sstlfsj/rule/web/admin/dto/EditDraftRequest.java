package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** 编辑规则草稿请求体：原地更新最新 DRAFT 版本（不增版本）。code/sceneCode 为身份不可改。 */
public record EditDraftRequest(
        @NotBlank String tenantId,
        String name,
        String kind,
        AstNode conditionAst,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes
) {}
