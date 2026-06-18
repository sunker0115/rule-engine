package com.sstlfsj.rule.kernel.api.model.ast;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

/**
 * 评分卡分段：score ∈ [minScore, maxScore) 时出 decisionCode + 决策名称/优先级（发布期回填）。
 * 非 AST 节点，是 ScorecardRootNode 的值对象，不进 @JsonSubTypes 多态体系。
 *
 * @param minScore     段下界（含）
 * @param maxScore     段上界（不含，左闭右开）
 * @param decisionCode 该段命中产出的决策码（发布期校验存在并回填 name/priority）
 * @param category     风险等级标签（如 HIGH_RISK），可空
 * @param name         决策名称（发布期从 decision_definition 回填，运行期直读，用户不填）
 * @param priority     决策优先级（发布期从 decision_definition 回填；primitive，缺键兜底 0）
 */
public record ScoreBand(
        double minScore, double maxScore,
        String decisionCode, String category,
        String name,
        @JsonSetter(nulls = Nulls.AS_EMPTY) int priority) {

    /** 4 参兼容构造：用于用户输入和测试（name/priority 发布期回填，初始为空/0）。 */
    public ScoreBand(double minScore, double maxScore, String decisionCode, String category) {
        this(minScore, maxScore, decisionCode, category, "", 0);
    }
}
