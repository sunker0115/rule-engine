package com.sstlfsj.rule.config.api.dto;

import com.sstlfsj.rule.kernel.api.model.RuleBody;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.DecisionBinding;
import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot.PreGateConfig;

import java.util.List;

/** 单个规则版本的完整内容（供历史版本查看 + diff，typed 内容直返不转 String）。 */
public record RuleVersionContentVO(
        Long ruleVersionId, Long version, String status, String kind,
        RuleBody body, List<DecisionBinding> decisionBindings,
        List<PreGateConfig> preGates, List<String> triggerEventTypes,
        String createdAt, String publishedBy, String publishedAt) {}
