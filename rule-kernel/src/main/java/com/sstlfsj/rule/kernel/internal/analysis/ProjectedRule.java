package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.model.RuleVersionSnapshot;

import java.util.Optional;

/**
 * 已投影规则：规则的立方体表示 + 其「有效决策」（最高优先级绑定）。
 *
 * <p>两两检测器（Overlap / Conflict / DeadRule）的统一比较单元——把可投影且有绑定的规则
 * 归一化为「立方体 + 单个有效决策码 + 该决策优先级」三元组。
 *
 * @param ruleCode              规则码
 * @param cube                  规则的取值空间立方体
 * @param effectiveDecisionCode 有效决策码（取最高优先级绑定的 decisionCode）
 * @param effectivePriority     有效决策优先级（越大越优先）
 */
public record ProjectedRule(String ruleCode, RuleCube cube,
                            String effectiveDecisionCode, int effectivePriority) {

    /**
     * 把可分析规则投影为比较单元。
     *
     * <p>当规则不可投影（{@link CubeProjector#project} 为空，如 OR/嵌套/非 AST_BOOLEAN）
     * 或无任何决策绑定（无法据此推断决策）时返回 {@link Optional#empty()}。
     * 有效决策取优先级最高的绑定，优先级相同则取列表中靠前者（稳定）。
     *
     * @param rule 待投影规则
     * @return 投影结果；不可投影或无绑定时为空
     */
    public static Optional<ProjectedRule> of(AnalyzableRule rule) {
        Optional<RuleCube> cube = CubeProjector.project(rule);
        if (cube.isEmpty()) {
            return Optional.empty();
        }
        // 取最高优先级绑定；优先级相等保留先出现者（不替换 → 稳定取首个）
        RuleVersionSnapshot.DecisionBinding effective = null;
        for (RuleVersionSnapshot.DecisionBinding binding : rule.bindings()) {
            if (effective == null || binding.priority() > effective.priority()) {
                effective = binding;
            }
        }
        if (effective == null) {
            return Optional.empty();
        }
        return Optional.of(new ProjectedRule(
                rule.ruleCode(), cube.get(), effective.decisionCode(), effective.priority()));
    }
}
