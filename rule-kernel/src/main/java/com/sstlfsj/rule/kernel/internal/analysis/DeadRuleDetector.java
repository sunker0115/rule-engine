package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.DeadRuleFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 死规则检测器：找出被更高优先级规则完全覆盖、其决策永不可能胜出的规则（掩盖 / subsumption）。
 *
 * <p>仅 {@code HIGHEST_PRIORITY} 与 {@code FIRST_HIT} 下有意义——对每个有序对 (A,B)（A≠B），
 * 当 {@code A.cube.subsumes(B.cube) == TRUE} 且 {@code A.effectivePriority > B.effectivePriority}（严格大于）时，
 * B 是死规则（凡 B 命中处 A 必命中且严格更高优先级胜出，B 决策永不浮现）。
 *
 * <p>{@code ALL_HITS} 下所有命中都收集、互不掩盖 → 返回空（区间矛盾导致的死规则属另一检测器）。
 * 等优先级 subsumption 不报：HIGHEST_PRIORITY 下等优先级相交属冲突，FIRST_HIT 下依赖分析期不可见的 tie-break，
 * 一律降级以保零误报。subsumes 为 UNKNOWN 时同样跳过。
 */
public final class DeadRuleDetector {

    private DeadRuleDetector() {}

    /**
     * 检测规则集中被高优先级规则掩盖的死规则。
     *
     * @param rules    待检测规则列表（不可投影 / 无绑定者自动剔除）
     * @param strategy 场景执行策略；ALL_HITS 下无掩盖语义，返回空
     * @return WARN 级死规则发现列表，按 (deadRuleCode, coveredByRuleCode) 升序确定性排列；无发现时为空
     */
    public static List<DeadRuleFinding> detect(List<AnalyzableRule> rules, SceneExecutionStrategy strategy) {
        List<DeadRuleFinding> findings = new ArrayList<>();
        // ALL_HITS：全量收集，规则间无掩盖关系
        if (strategy == SceneExecutionStrategy.ALL_HITS) {
            return findings;
        }
        List<ProjectedRule> projected = DetectorSupport.project(rules);
        for (ProjectedRule a : projected) {
            for (ProjectedRule b : projected) {
                if (a == b) {
                    continue;
                }
                // A 完全包含 B 且严格更高优先级 → B 死；严格 > 天然规避 A↔B 互含时双向重复报。
                // FIRST_HIT 同样以 priority 严格大于作为保守掩盖判据：等优先级的 tie-break 顺序分析期不可见，故不报，与 HIGHEST_PRIORITY 一致。
                if (a.cube().subsumes(b.cube()) == Tri.TRUE
                        && a.effectivePriority() > b.effectivePriority()) {
                    String reason = b.ruleCode() + " 被 " + a.ruleCode()
                            + " 完全覆盖且优先级更低，其决策永不胜出（死规则）";
                    findings.add(new DeadRuleFinding(b.ruleCode(), a.ruleCode(), reason, Severity.WARN));
                }
            }
        }
        findings.sort(Comparator.comparing(DeadRuleFinding::deadRuleCode)
                .thenComparing(DeadRuleFinding::coveredByRuleCode));
        return findings;
    }
}
