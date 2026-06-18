package com.sstlfsj.rule.kernel.api.analysis;

import java.util.List;

/**
 * scene 级规则集静态分析结果,聚合各类发现,返回至 HTTP 层供前端展示。
 *
 * @param redundancies 单规则内被同组另一条件蕴含的冗余条件（可简化提示），见 {@link RedundancyFinding}
 */
public record RuleSetAnalysisReport(
        String sceneCode,
        List<IncoherenceFinding> incoherences,
        List<DeadRuleFinding> deadRules,
        List<ConflictFinding> conflicts,
        List<OverlapFinding> overlaps,
        List<CoverageGapFinding> coverageGaps,
        List<UnanalyzableRule> unanalyzableRules,
        List<RedundancyFinding> redundancies
) {
}
