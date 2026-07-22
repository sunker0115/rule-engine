package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;
import com.sstlfsj.rule.kernel.api.model.ast.AstNode;
import com.sstlfsj.rule.kernel.api.model.flow.FlowGraph;

import java.util.List;

/**
 * kernel 分析的轻量规则输入,由 config 编排层从 RuleVersion 拆出,避免 kernel 依赖 config 实体。
 *
 * @param flowGraph DECISION_FLOW 规则的决策图(供环检测/可达性分析);非 flow 规则为 null
 */
public record AnalyzableRule(
        String ruleCode,
        long version,
        AstNode ast,
        List<RuleVersionSnapshot.DecisionBinding> bindings,
        String kind,
        FlowGraph flowGraph
) {

    /** 非 DECISION_FLOW 规则的便捷构造(flowGraph 缺省为 null),供既有 AST/表/评分卡调用点使用。 */
    public AnalyzableRule(String ruleCode, long version, AstNode ast,
                          List<RuleVersionSnapshot.DecisionBinding> bindings, String kind) {
        this(ruleCode, version, ast, bindings, kind, null);
    }
}
