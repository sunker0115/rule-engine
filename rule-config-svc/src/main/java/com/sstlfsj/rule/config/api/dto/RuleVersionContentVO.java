package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;
import com.sstlfsj.rule.kernel.api.model.ScriptSource;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;

import java.util.List;

/** 单个规则版本的完整内容（供历史版本查看 + diff，typed 内容直返不转 String）。 */
public record RuleVersionContentVO(
        Long ruleVersionId, Long version, String status, String kind,
        AstNode conditionAst, List<DecisionBinding> decisionBindings,
        List<PreGateConfig> preGates, List<String> triggerEventTypes,
        ScriptSource script,
        FlowGraph flowGraph,
        String createdAt, String publishedBy, String publishedAt) {}
