package com.sstlfsj.rule.kernel.internal.analysis;

import com.sstlfsj.rule.kernel.api.analysis.ConflictFinding;
import com.sstlfsj.rule.kernel.api.analysis.CoverageGapFinding;
import com.sstlfsj.rule.kernel.api.analysis.DeadRuleFinding;
import com.sstlfsj.rule.kernel.api.analysis.IncoherenceFinding;
import com.sstlfsj.rule.kernel.api.analysis.OverlapFinding;
import com.sstlfsj.rule.kernel.api.analysis.RuleSetAnalysisReport;
import com.sstlfsj.rule.kernel.api.analysis.UnanalyzableRule;
import com.sstlfsj.rule.kernel.api.model.RuleKind;
import com.sstlfsj.rule.kernel.api.model.SceneExecutionStrategy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 规则集静态分析编排器：把 6 个 detector 装配为单一 {@link RuleSetAnalysisReport}。
 *
 * <p>编排顺序：矛盾（incoherence）/ 决策可达性缺口（coverageGap）整集分析；
 * overlap / dead / conflict 三类两两分析（仅对可投影的扁平 AST_BOOLEAN 立方体）；
 * 决策表行内分析（DMN 风格）。两两结果与决策表行内结果<b>合并</b>进报告对应列表后，
 * 各按 detector 同一比较器确定性排序（overlap/conflict 按 (locA,locB)，dead 按
 * (deadRuleCode,coveredByRuleCode)）。
 *
 * <p><b>unanalyzableRules 语义</b>：列入此表表示该规则<b>未被 overlap/dead/conflict
 * 立方体两两分析覆盖</b>——并非"无问题"，而是"不能假设其与其它规则无冲突"。判定：
 * 可投影的扁平 AST_BOOLEAN（{@link CubeProjector#project} 有结果）→ 已分析；
 * DECISION_TABLE → 已（行内）分析；其余按 kind 给不可分析原因。
 * 注意一条规则即便列入 unanalyzableRules，仍可能出现在 coverageGaps / incoherences 中
 * （它们走各自的整集/可投影分析路径，与两两立方体分析相互独立）。
 */
public final class RuleSetAnalyzer {

    private static final Comparator<OverlapFinding> OVERLAP_ORDER =
            Comparator.comparing(OverlapFinding::locA).thenComparing(OverlapFinding::locB);
    private static final Comparator<ConflictFinding> CONFLICT_ORDER =
            Comparator.comparing(ConflictFinding::locA).thenComparing(ConflictFinding::locB);
    private static final Comparator<DeadRuleFinding> DEAD_ORDER =
            Comparator.comparing(DeadRuleFinding::deadRuleCode).thenComparing(DeadRuleFinding::coveredByRuleCode);
    private static final Comparator<IncoherenceFinding> INCOHERENCE_ORDER =
            Comparator.comparing(IncoherenceFinding::ruleCode);

    private RuleSetAnalyzer() {}

    /**
     * 对一个场景的规则集执行全部静态分析，装配最终报告。
     *
     * @param sceneCode 场景标识，原样回填报告
     * @param rules     待分析规则列表；{@code null} / 空时返回各列表均为空的报告
     * @param strategy  场景执行策略，决定 dead / conflict 的歧义判定
     * @return 聚合各类发现的 {@link RuleSetAnalysisReport}；所有列表确定性排序，永不为 null
     */
    public static RuleSetAnalysisReport analyze(String sceneCode,
                                                List<AnalyzableRule> rules,
                                                SceneExecutionStrategy strategy) {
        if (rules == null || rules.isEmpty()) {
            return new RuleSetAnalysisReport(sceneCode,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        List<IncoherenceFinding> incoherences = new ArrayList<>(IncoherenceDetector.detect(rules));
        List<CoverageGapFinding> coverageGaps = CoverageGapDetector.detect(rules);

        // 两两立方体分析（仅扁平 AST_BOOLEAN）
        List<OverlapFinding> overlaps = new ArrayList<>(OverlapDetector.detect(rules, strategy));
        List<DeadRuleFinding> deadRules = new ArrayList<>(DeadRuleDetector.detect(rules, strategy));
        List<ConflictFinding> conflicts = new ArrayList<>(ConflictDetector.detect(rules, strategy));

        // 决策表行内分析，合并入对应列表后重新排序
        DecisionTableDetector.DecisionTableFindings table = DecisionTableDetector.detect(rules);
        overlaps.addAll(table.overlaps());
        conflicts.addAll(table.conflicts());
        deadRules.addAll(table.deadRows());
        incoherences.addAll(table.incoherences());
        overlaps.sort(OVERLAP_ORDER);
        conflicts.sort(CONFLICT_ORDER);
        deadRules.sort(DEAD_ORDER);
        incoherences.sort(INCOHERENCE_ORDER);

        List<UnanalyzableRule> unanalyzable = collectUnanalyzable(rules);

        return new RuleSetAnalysisReport(sceneCode,
                incoherences, deadRules, conflicts, overlaps, coverageGaps, unanalyzable);
    }

    /** 收集未被立方体两两分析覆盖、且非决策表的规则，按 ruleCode 升序确定性排列。 */
    private static List<UnanalyzableRule> collectUnanalyzable(List<AnalyzableRule> rules) {
        List<UnanalyzableRule> unanalyzable = new ArrayList<>();
        for (AnalyzableRule rule : rules) {
            // 可投影扁平 AST_BOOLEAN → 已被两两分析覆盖；决策表 → 已被行内分析覆盖。二者均不列入。
            if (CubeProjector.project(rule).isPresent()
                    || RuleKind.DECISION_TABLE.tag().equals(rule.kind())) {
                continue;
            }
            unanalyzable.add(new UnanalyzableRule(rule.ruleCode(), reasonFor(rule)));
        }
        unanalyzable.sort(Comparator.comparing(UnanalyzableRule::ruleCode));
        return unanalyzable;
    }

    /** 按规则种类给出"为何不可静态分析"的原因；未知 kind 走通用原因。 */
    private static String reasonFor(AnalyzableRule rule) {
        String kind = rule.kind();
        if (RuleKind.AST_BOOLEAN.tag().equals(kind)) {
            // 此分支只会命中不可投影的 AST_BOOLEAN（可投影者上层已 continue）
            return "含 OR/NOT/XOR/嵌套或非扁平 AND，超出 v1 区间分析";
        }
        if (RuleKind.DECISION_TREE.tag().equals(kind)) {
            return "决策树跨树区间分析 v1 未做";
        }
        if (RuleKind.SCORECARD.tag().equals(kind)) {
            return "评分卡按加权分，不参与区间分析";
        }
        if (RuleKind.EXPRESSION_SCRIPT.tag().equals(kind)) {
            return "脚本规则无法静态推理";
        }
        return kind + " 暂不支持静态分析";
    }
}
