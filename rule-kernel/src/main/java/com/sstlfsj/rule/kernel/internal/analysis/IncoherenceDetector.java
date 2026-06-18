package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.IncoherenceFinding;
import com.sstlfsj.rule.kernel.api.analysis.Severity;

import java.util.ArrayList;
import java.util.List;

/**
 * 矛盾规则检测器：找出自身条件相互矛盾、永不命中的规则。
 *
 * <p>对每条可投影（{@link CubeProjector#project} 有结果）且立方体 {@link RuleCube#isIncoherent()}
 * 的规则产出一条 {@link Severity#ERROR} 级 {@link IncoherenceFinding}。
 * 不可投影的规则在此跳过——由编排层统一报告为不可分析，不属本检测器职责。
 */
public final class IncoherenceDetector {

    private IncoherenceDetector() {}

    /**
     * 检测规则集中的矛盾规则。
     *
     * @param rules 待检测规则列表
     * @return 矛盾规则的发现列表（每条矛盾规则一项），无矛盾时为空列表
     */
    public static List<IncoherenceFinding> detect(List<AnalyzableRule> rules) {
        List<IncoherenceFinding> findings = new ArrayList<>();
        for (AnalyzableRule rule : rules) {
            CubeProjector.project(rule)
                    .filter(RuleCube::isIncoherent)
                    .ifPresent(cube -> {
                        String dim = cube.firstEmptyDim().orElseThrow();
                        String reason = "维度 " + dim + " 上条件相互矛盾，规则永不命中";
                        findings.add(new IncoherenceFinding(rule.ruleCode(), reason, Severity.ERROR));
                    });
        }
        return findings;
    }
}
