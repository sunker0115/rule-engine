package com.sstlfsj.rule.kernel.api.model.ast;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

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
    /**
     * 规范构造器显式标 @JsonCreator——本 record 有 2 参兼容构造，
     * 不标注时 Jackson 反序列化会选错构造器（漏读 bands）。type 判别由 AstNode 的 @JsonTypeInfo 处理。
     */
    @JsonCreator
    public ScorecardRootNode(
            @JsonProperty("conditions") List<ConditionNode> conditions,
            @JsonProperty("threshold") double threshold,
            @JsonProperty("bands") List<ScoreBand> bands) {
        this.conditions = conditions == null ? List.of() : List.copyOf(conditions);
        this.threshold = threshold;
        this.bands = bands == null ? List.of() : List.copyOf(bands);
    }

    /** 兼容构造：无 bands，现有 2 参调用点不变。 */
    public ScorecardRootNode(List<ConditionNode> conditions, double threshold) {
        this(conditions, threshold, List.of());
    }
}
