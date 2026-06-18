package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.ConflictFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 冲突检测器：找出输入区间相交但产出不同决策、且在该执行策略下结果真歧义的规则对。
 *
 * <p>对每个无序规则对 (A,B)（A 先于 B），仅当 {@code A.cube.overlaps(B.cube) == TRUE}
 * 且两者有效决策码不同时，按策略判定是否构成冲突：
 * <ul>
 *   <li>{@code ALL_HITS}：两条都命中、两个不同决策都产出 → 冲突。</li>
 *   <li>{@code HIGHEST_PRIORITY}：仅当优先级相等（相交区域内谁胜出歧义）才冲突；
 *       优先级不同则高者确定性胜出 → 非冲突。</li>
 *   <li>{@code FIRST_HIT}：在先规则确定性赢得相交区域 → 不歧义，跳过（保守不报）。</li>
 * </ul>
 * 相交结果为 UNKNOWN 时保守跳过（零误报）。
 */
public final class ConflictDetector {

    private ConflictDetector() {}

    /**
     * 检测规则集中的决策冲突对。
     *
     * @param rules    待检测规则列表（不可投影 / 无绑定者自动剔除）
     * @param strategy 场景执行策略，决定相交+异决策对是否构成歧义冲突
     * @return WARN 级冲突发现列表，按 (locA, locB) 升序确定性排列；无发现时为空
     */
    public static List<ConflictFinding> detect(List<AnalyzableRule> rules, SceneExecutionStrategy strategy) {
        List<ConflictFinding> findings = new ArrayList<>();
        // FIRST_HIT：在先规则确定性胜出相交区域，无歧义，整体不报
        if (strategy == SceneExecutionStrategy.FIRST_HIT) {
            return findings;
        }
        List<ProjectedRule> projected = DetectorSupport.project(rules);
        for (int i = 0; i < projected.size(); i++) {
            for (int j = i + 1; j < projected.size(); j++) {
                ProjectedRule a = projected.get(i);
                ProjectedRule b = projected.get(j);
                if (a.cube().overlaps(b.cube()) != Tri.TRUE
                        || a.effectiveDecisionCode().equals(b.effectiveDecisionCode())) {
                    continue;
                }
                if (isConflict(strategy, a, b)) {
                    String reason = a.ruleCode() + " 与 " + b.ruleCode()
                            + " 输入区间相交但产出不同决策(" + a.effectiveDecisionCode()
                            + " / " + b.effectiveDecisionCode() + ")，存在歧义";
                    findings.add(new ConflictFinding(a.ruleCode(), b.ruleCode(),
                            a.effectiveDecisionCode(), b.effectiveDecisionCode(), reason, Severity.WARN));
                }
            }
        }
        findings.sort(Comparator.comparing(ConflictFinding::locA).thenComparing(ConflictFinding::locB));
        return findings;
    }

    /** 在 ALL_HITS（两决策都出）或 HIGHEST_PRIORITY 且优先级相等（谁胜出歧义）时判为冲突。 */
    private static boolean isConflict(SceneExecutionStrategy strategy, ProjectedRule a, ProjectedRule b) {
        return switch (strategy) {
            case ALL_HITS -> true;
            case HIGHEST_PRIORITY -> a.effectivePriority() == b.effectivePriority();
            case FIRST_HIT -> false; // 上层已提前返回，留作 switch 全覆盖
        };
    }
}
