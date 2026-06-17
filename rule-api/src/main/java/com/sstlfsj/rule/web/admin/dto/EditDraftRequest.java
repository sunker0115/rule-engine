package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 编辑规则草稿请求体：原地更新最新 DRAFT 版本（不增版本）。code/sceneCode 为身份不可改。EXPRESSION_SCRIPT 时传 script。 */
public record EditDraftRequest(
        @NotNull Long tenantId,
        String name,
        String kind,
        AstNode conditionAst,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes,
        ScriptSource script
) {}
