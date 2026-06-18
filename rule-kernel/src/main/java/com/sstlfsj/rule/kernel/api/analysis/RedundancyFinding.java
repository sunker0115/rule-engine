package com.sstlfsj.rule.kernel.api.analysis;

/**
 * 单规则内一个条件被同组（同一 AND-of-condition 组）另一条件蕴含 → 冗余，可简化删除。
 *
 * <p>例：{@code amount <= 10 AND amount == 10} 中 {@code amount <= 10} 被 {@code amount == 10} 蕴含（后者更严格），
 * 故 {@code <= 10} 冗余。
 *
 * @param ruleCode            所属规则编码
 * @param redundantCondition  冗余条件的人类可读描述（{@code metricCode 算子 关键参数值}）
 * @param impliedByCondition  蕴含它的更严格条件的人类可读描述
 * @param reason              冗余原因（中文）
 * @param severity            严重度（冗余为可简化提示，恒为 {@link Severity#INFO}）
 */
public record RedundancyFinding(
        String ruleCode,
        String redundantCondition,
        String impliedByCondition,
        String reason,
        Severity severity
) {
}
