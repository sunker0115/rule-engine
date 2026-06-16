package com.sstlfsj.rule.kernel.api.model.ast;

import java.util.List;

/**
 * 评分卡根节点：持有叶子条件列表（各自带 weight）、命中阈值、分数段。
 * kind=SCORECARD 的规则 conditionAst 顶层节点为此类型。
 *
 * <p>单规范构造器（与其它 AST 节点统一）：不设兼容重载——多构造器 record 在
 * fail-on-unknown=false 的 Spring ObjectMapper 下会选错构造器静默丢字段（bands），
 * 故保持单构造器消除反序列化歧义。</p>
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
}
