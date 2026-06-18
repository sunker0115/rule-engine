package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/** 规则详情：规则定义基本信息 + 当前版本（DRAFT 优先）的条件 AST / 决策绑定 / 前置门控 / 触发事件 + 全部版本时间线。 */
public record RuleDetailVO(
        Long tenantId,
        Long ruleDefinitionId, String code, String name, String status, String kind,
        String sceneCode, AstNode conditionAst, List<DecisionBinding> decisionBindings,
        List<PreGateConfig> preGates,
        List<String> triggerEventTypes,
        ScriptSource script,
        Long currentVersionId,
        List<VersionItem> versions) {

    /** 版本时间线条目 */
    public record VersionItem(Long ruleVersionId, Long version, String status,
                              String createdAt, String publishedBy, String publishedAt) {}
}
