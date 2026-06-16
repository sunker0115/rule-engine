package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/**
 * 评分卡根节点：持有叶子条件列表（各自带 weight）和命中阈值。
 * kind=SCORECARD 的规则 conditionAst 顶层节点为此类型。
 */
public record ScorecardRootNode(
        /** 评分卡叶子条件列表（元素均为 ConditionNode，带各自 weight）。 */
        List<ConditionNode> conditions,
        /** 命中门槛：score < threshold 则规则不命中。 */
        double threshold,
        /** 分数段→决策列表，空表示不分段（仅按 threshold 单命中）。 */
        List<ScoreBand> bands
) implements AstNode {
    public ScorecardRootNode {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        bands = bands == null ? List.of() : List.copyOf(bands);
    }

    /** 兼容构造：无 bands，现有 2 参调用点不变。 */
    public ScorecardRootNode(List<ConditionNode> conditions, double threshold) {
        this(conditions, threshold, List.of());
    }
}
