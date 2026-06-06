package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/**
 * 评分卡根节点：持有叶子条件列表（各自带 weight）和命中阈值。
 * kind=SCORECARD 的规则 conditionAst 顶层节点为此类型。
 */
public record ScorecardRootNode(
        /** 评分卡叶子条件列表（元素均为 ConditionNode，带各自 weight）。 */
        List<ConditionNode> conditions,
        /** 规则命中所需最低分（满足 score >= threshold 则 ruleHit=true）。 */
        double threshold
) implements AstNode {
    public ScorecardRootNode {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
    }
}
