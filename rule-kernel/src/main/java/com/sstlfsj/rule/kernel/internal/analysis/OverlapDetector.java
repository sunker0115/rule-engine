package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.OverlapFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 冗余重叠检测器：找出输入区间相交且产出相同决策的规则对（可合并提示）。
 *
 * <p>对每个无序规则对 (A,B)（A 在输入中先于 B），当 {@code A.cube.overlaps(B.cube) == TRUE}
 * 且两者有效决策码相同时，产出一条 {@link Severity#INFO} 级 {@link OverlapFinding}。
 * 决策不同属冲突检测职责（此处不报）；相交结果为 UNKNOWN 时保守跳过（零误报）。
 * 与执行策略无关。
 */
public final class OverlapDetector {

    private OverlapDetector() {}

    /**
     * 检测规则集中决策相同的冗余重叠规则对。
     *
     * @param rules    待检测规则列表（不可投影 / 无绑定者自动剔除）
     * @param strategy 场景执行策略（本检测器与策略无关，仅为统一签名保留）
     * @return INFO 级重叠发现列表，按 (locA, locB) 升序确定性排列；无发现时为空
     */
    public static List<OverlapFinding> detect(List<AnalyzableRule> rules, SceneExecutionStrategy strategy) {
        List<ProjectedRule> projected = DetectorSupport.project(rules);
        List<OverlapFinding> findings = new ArrayList<>();
        for (int i = 0; i < projected.size(); i++) {
            for (int j = i + 1; j < projected.size(); j++) {
                ProjectedRule a = projected.get(i);
                ProjectedRule b = projected.get(j);
                if (a.cube().overlaps(b.cube()) == Tri.TRUE
                        && a.effectiveDecisionCode().equals(b.effectiveDecisionCode())) {
                    String reason = a.ruleCode() + " 与 " + b.ruleCode()
                            + " 输入区间相交且决策相同(" + a.effectiveDecisionCode() + ")，可考虑合并";
                    findings.add(new OverlapFinding(a.ruleCode(), b.ruleCode(), reason, Severity.INFO));
                }
            }
        }
        findings.sort(Comparator.comparing(OverlapFinding::locA).thenComparing(OverlapFinding::locB));
        return findings;
    }
}
