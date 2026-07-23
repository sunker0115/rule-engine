package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 编辑规则草稿请求体：原地更新最新 DRAFT 版本（不增版本）。code/sceneCode 为身份不可改。body 为多态载体（与 kind 一致）。 */
public record EditDraftRequest(
        @NotNull Long tenantId,
        String name,
        String kind,
        RuleBody body,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes
) implements RuleContentSource {}
