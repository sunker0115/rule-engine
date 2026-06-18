package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;

import java.util.List;

/** kernel 分析的轻量规则输入,由 config 编排层从 RuleVersion 拆出,避免 kernel 依赖 config 实体。 */
public record AnalyzableRule(
        String ruleCode,
        long version,
        AstNode ast,
        List<RuleVersionSnapshot.DecisionBinding> bindings,
        String kind
) {
}
