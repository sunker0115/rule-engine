package com.sstlfsj.rule.kernel.api.model.ast;

/**
 * 评分卡分段：score ∈ [minScore, maxScore) 时出 decisionCode（带 category 风险等级）。
 * 非 AST 节点，是 ScorecardRootNode 的值对象，不进 @JsonSubTypes 多态体系。
 *
 * @param minScore     段下界（含）
 * @param maxScore     段上界（不含，左闭右开）
 * @param decisionCode 该段命中产出的决策码（发布期校验存在并回填 name/priority）
 * @param category     风险等级标签（如 HIGH_RISK），可空
 */
public record ScoreBand(double minScore, double maxScore, String decisionCode, String category) {}
