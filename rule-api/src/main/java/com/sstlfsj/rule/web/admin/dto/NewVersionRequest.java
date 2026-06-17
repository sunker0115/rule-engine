package com.sstlfsj.rule.web.admin.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** 出新版本草稿请求体。fromVersionId 非空时为回退（克隆该版本内容，忽略下面的内容字段含 script）。 */
public record NewVersionRequest(
        @NotNull Long tenantId,
        String name,
        String kind,
        AstNode conditionAst,
        List<DecisionBindingInput> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes,
        Long fromVersionId,
        ScriptSource script
) {}
